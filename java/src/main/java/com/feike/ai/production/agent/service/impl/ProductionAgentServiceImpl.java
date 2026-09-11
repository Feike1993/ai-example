package com.feike.ai.production.agent.service.impl;

import com.feike.ai.production.agent.manager.ProductionTools;
import com.feike.ai.production.agent.service.ProductionAgentLoop;
import com.feike.ai.production.agent.service.ProductionAgentService;

import com.feike.ai.core.context.ContextBudget;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.chat.service.SessionBusyException;
import com.feike.ai.production.guardrail.service.GuardrailBlockedException;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.lock.manager.SessionLock;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.ratelimit.manager.IdempotencyManager;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.session.dao.ProductionChatSessionDAO;
import com.feike.ai.production.sse.service.SseStreamWriter;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产 Agent 编排：与问答共用会话锁和落库规则，多出发 {@code step} 事件。
 */
public class ProductionAgentServiceImpl implements ProductionAgentService {

    private static final Logger log = LoggerFactory.getLogger(ProductionAgentServiceImpl.class);

    private final ProductionModelFactory models;
    private final ProductionRetrievalService retrieval;
    private final ProductionIngestService ingest;
    private final ProductionProperties properties;
    private final ProductionChatSessionDAO sessionStore;
    private final SessionLock sessionLock;
    private final ProductionGuardrail guardrail;
    private final ProductionMetrics metrics;
    private final AuditService audit;

    /**
     * @param models       模型
     * @param retrieval    检索（注入工具）
     * @param ingest       入库（管理员工具）
     * @param properties   配置
     * @param sessionStore 会话
     * @param sessionLock  锁
     * @param guardrail    护栏
     * @param metrics      工具拒绝计数；可空
     * @param audit        逐步审计；可空
     */
    public ProductionAgentServiceImpl(
        ProductionModelFactory models,
        ProductionRetrievalService retrieval,
        ProductionIngestService ingest,
        ProductionProperties properties,
        ProductionChatSessionDAO sessionStore,
        SessionLock sessionLock,
        ProductionGuardrail guardrail,
        ProductionMetrics metrics,
        AuditService audit
    ) {
        this.models = models;
        this.retrieval = retrieval;
        this.ingest = ingest;
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.sessionLock = sessionLock;
        this.guardrail = guardrail;
        this.metrics = metrics;
        this.audit = audit;
    }

    /**
     * 同步运行。
     *
     * @param principal 用户
     * @param sessionId 会话
     * @param question  任务
     * @param provider  模型
     * @return 终答与步骤
     */
    public AgentAnswer run(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    ) {
        guardrail.checkInput(question);
        String id = resolve(sessionId);
        String runId = UUID.randomUUID().toString();
        if (id != null) {
            try (SessionLock.Handle ignored = acquire(id)) {
                return runUnlocked(principal, id, question, provider, runId);
            }
        }
        return runUnlocked(principal, null, question, provider, runId);
    }

    /**
     * 流式运行。
     *
     * @param writer    SSE
     * @param principal 用户
     * @param sessionId 会话
     * @param question  任务
     * @param provider  模型
     */
    public void stream(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    ) {
        try {
            guardrail.checkInput(question);
        } catch (GuardrailBlockedException ex) {
            writer.error(ex.code(), ex.getMessage());
            return;
        }
        String id = resolve(sessionId);
        if (id == null) {
            streamUnlocked(writer, principal, null, question, provider);
            return;
        }
        Optional<SessionLock.Handle> handle = sessionLock.tryAcquire(id);
        if (handle.isEmpty()) {
            writer.error("session_busy", "该会话已有一轮对话正在进行，请等待其完成后再试");
            return;
        }
        try (SessionLock.Handle ignored = handle.get()) {
            streamUnlocked(writer, principal, id, question, provider);
        }
    }

    private void streamUnlocked(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    ) {
        try {
            List<Message> history = loadHistory(principal, sessionId);
            org.slf4j.MDC.put("runId", writer.runId());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("provider", provider);
            meta.put("sessionId", sessionId);
            meta.put("tenant", principal.tenantId());
            meta.put("mode", "agent");
            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId != null) {
                meta.put("traceId", traceId);
            }
            writer.meta(meta);

            AtomicInteger denied = new AtomicInteger();
            ProductionTools tools = new ProductionTools(retrieval, ingest, principal, properties);
            ProductionAgentLoop.Trace trace = ProductionAgentLoop.run(
                models.chatModel(provider),
                tools,
                principal,
                question,
                history,
                properties.agent().maxSteps(),
                step -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("客户端已断开");
                    }
                    if (step.denied()) {
                        denied.incrementAndGet();
                        if (metrics != null) {
                            metrics.toolDenied();
                        }
                    }
                    auditStep(principal, writer.runId(), step);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("index", step.index());
                    payload.put("toolName", step.toolName());
                    payload.put("assistantText", step.assistantText());
                    payload.put("toolArgs", step.toolArgs());
                    payload.put("toolResult", step.toolResult());
                    payload.put("denied", step.denied());
                    writer.emit(StreamEventTypeEnum.STEP, payload);
                }
            );
            String answer = trace.finalAnswer() == null ? "" : trace.finalAnswer();
            guardrail.checkOutput(answer);
            writer.delta(answer);
            writer.emit(StreamEventTypeEnum.USAGE, Map.of(
                "answerChars", answer.length(),
                "sourceCount", 0,
                "steps", trace.steps().size(),
                "toolDenied", denied.get()
            ));
            boolean persisted = persist(principal, sessionId, writer.runId(), question, answer);
            writer.done(Map.of(
                "sessionId", sessionId,
                "persisted", persisted,
                "reachedMaxSteps", trace.reachedMaxSteps()
            ));
        } catch (CancellationException ex) {
            log.info("agent run={} 被取消", writer.runId());
            writer.cancel();
        } catch (GuardrailBlockedException ex) {
            writer.error(ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("agent run={} 失败", writer.runId(), ex);
            writer.error("upstream_error", "Agent 失败：" + ex.getMessage());
        }
    }

    private AgentAnswer runUnlocked(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        String runId
    ) {
        List<Message> history = loadHistory(principal, sessionId);
        ProductionTools tools = new ProductionTools(retrieval, ingest, principal, properties);
        ProductionAgentLoop.Trace trace = ProductionAgentLoop.run(
            models.chatModel(provider),
            tools,
            principal,
            question,
            history,
            properties.agent().maxSteps(),
            null
        );
        String answer = trace.finalAnswer() == null ? "" : trace.finalAnswer();
        for (ProductionAgentLoop.Step step : trace.steps()) {
            if (step.denied() && metrics != null) {
                metrics.toolDenied();
            }
            auditStep(principal, runId, step);
        }
        guardrail.checkOutput(answer);
        boolean persisted = persist(principal, sessionId, runId, question, answer);
        return new AgentAnswer(sessionId, answer, trace.steps(), trace.reachedMaxSteps(), persisted, runId);
    }

    private String resolve(String sessionId) {
        if (sessionStore == null || sessionLock == null || !properties.session().enabled()) {
            return null;
        }
        return sessionStore.resolveSessionId(sessionId);
    }

    private SessionLock.Handle acquire(String sessionId) {
        return sessionLock.tryAcquire(sessionId).orElseThrow(() -> new SessionBusyException(sessionId));
    }

    private List<Message> loadHistory(ProductionPrincipal principal, String sessionId) {
        if (sessionId == null || sessionStore == null || !properties.session().enabled()) {
            return List.of();
        }
        return ContextBudget.trim(
            sessionStore.historyMessages(principal.tenantId(), sessionId),
            properties.session().maxMessages(),
            properties.session().tokenBudget()
        ).messages();
    }

    private boolean persist(
        ProductionPrincipal principal,
        String sessionId,
        String runId,
        String question,
        String answer
    ) {
        if (sessionId == null || sessionStore == null || !properties.session().enabled()) {
            return false;
        }
        try {
            sessionStore.appendTurn(principal.tenantId(), sessionId, UUID.randomUUID(), runId, question, answer);
            return true;
        } catch (RuntimeException ex) {
            log.error("agent session={} 落库失败", sessionId, ex);
            return false;
        }
    }

    private void auditStep(ProductionPrincipal principal, String runId, ProductionAgentLoop.Step step) {
        if (audit == null || step == null) {
            return;
        }
        String argsHash = step.toolArgs() == null ? null : IdempotencyManager.sha256(step.toolArgs());
        audit.record(
            principal.tenantId(),
            principal.subject(),
            "agent.tool",
            "/api/v1/agent",
            200,
            runId,
            argsHash,
            null,
            null,
            step.toolName(),
            step.denied()
        );
    }

}
