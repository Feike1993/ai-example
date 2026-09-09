package com.feike.ai.production.chat.controller;

import com.feike.ai.production.chat.model.ChatRequestDTO;
import com.feike.ai.production.chat.model.SessionVO;
import com.feike.ai.production.chat.service.ProductionChatService;

import com.feike.ai.production.agent.service.ProductionAgentService;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.ratelimit.manager.IdempotencyManager;
import com.feike.ai.production.ratelimit.service.RateLimitExceededException;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.sse.service.SseRunExecutor;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.slf4j.MDC;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 工业级链路 HTTP 入口，统一挂在 {@code /api/v1/**}。
 * <p>
 * 与样例路径（{@code /rag}、{@code /chat}、{@code /agent}）完全不重叠。
 * 身份由 {@link JwtAuthFilter} 放入 request，再作为显式参数下传到编排层。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionChatController {

    private final ProductionChatService chatService;
    private final ProductionIngestService ingestService;
    private final SseRunExecutor runExecutor;
    private final ProductionAgentService agentService;
    private final RedisTokenBucket bucket;
    private final IdempotencyManager idempotency;
    private final AuditService audit;
    private final ProductionMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;

    /**
     * 单测用的窄构造：不接限流 / 审计 / Agent。
     *
     * @param chatService   编排
     * @param ingestService 入库
     * @param runExecutor   SSE
     */
    ProductionChatController(
        ProductionChatService chatService,
        ProductionIngestService ingestService,
        SseRunExecutor runExecutor
    ) {
        this(chatService, ingestService, runExecutor, null, null, null, null, null, null, null);
    }

    /**
     * @param chatService   问答
     * @param ingestService 入库
     * @param runExecutor   SSE
     * @param agentService  Agent
     * @param bucket        限流
     * @param idempotency   幂等
     * @param audit         审计
     * @param metrics       指标
     * @param jsonMapper    幂等哈希
     * @param tracer        可选 Trace
     */
    @Autowired
    public ProductionChatController(
        ProductionChatService chatService,
        ProductionIngestService ingestService,
        SseRunExecutor runExecutor,
        ProductionAgentService agentService,
        RedisTokenBucket bucket,
        IdempotencyManager idempotency,
        AuditService audit,
        ProductionMetrics metrics,
        JsonMapper jsonMapper,
        ObjectProvider<Tracer> tracer
    ) {
        this.chatService = chatService;
        this.ingestService = ingestService;
        this.runExecutor = runExecutor;
        this.agentService = agentService;
        this.bucket = bucket;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer == null ? null : tracer.getIfAvailable();
    }

    /**
     * 幂等重建生产语料索引。仅 ADMIN。
     *
     * @param http HTTP
     * @return 入库结果
     */
    @PostMapping("/rag/ingest")
    public ProductionIngestService.IngestResult ingest(HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        if (!principal.admin()) {
            throw new BusinessException(ErrorCodeEnum.INGEST_FORBIDDEN);
        }
        ProductionIngestService.IngestResult result = ingestService.ingest();
        audit(principal, "rag.ingest", http, 200, null, null);
        return result;
    }

    /**
     * 同步问答。
     *
     * @param request          请求体
     * @param http             身份
     * @param response         限流头
     * @param idempotencyKey   可选幂等键
     * @return 答案与来源
     */
    @PostMapping("/chat")
    public ProductionChatService.ChatAnswer chat(
        @Valid @RequestBody ChatRequestDTO request,
        HttpServletRequest http,
        HttpServletResponse response,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        String bodyHash = hashBody(request);
        Optional<String> cached = beginIdempotency(principal, idempotencyKey, bodyHash);
        if (cached.isPresent()) {
            try {
                return jsonMapper.readValue(cached.get(), ProductionChatService.ChatAnswer.class);
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCodeEnum.IDEMPOTENCY_CONFLICT, "幂等缓存损坏");
            }
        }
        long start = System.nanoTime();
        if (metrics != null) {
            metrics.chatRun();
        }
        ProductionChatService.ChatAnswer answer = chatService.answer(
            principal, request.sessionId(), request.question(), request.provider(), request.topK());
        if (metrics != null) {
            metrics.recordDuration(System.nanoTime() - start);
            if (answer.retrievalEmpty()) {
                metrics.emptyRetrieval();
            }
        }
        completeIdempotency(principal, idempotencyKey, bodyHash, answer);
        audit(principal, "chat", http, 200, null, request.question());
        return answer;
    }

    /**
     * 流式问答。
     *
     * @param question  问题
     * @param sessionId 会话
     * @param provider  模型
     * @param topK      topK
     * @param http      身份
     * @param response  限流头
     * @return SSE
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
        @RequestParam @NotBlank(message = "问题不能为空") String question,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        if (metrics != null) {
            metrics.chatRun();
        }
        attachTrace(response);
        audit(principal, "chat.stream", http, 200, null, question);
        ProductionPrincipal frozen = principal;
        return runExecutor.start(writer ->
            chatService.streamAnswer(writer, frozen, sessionId, question, provider, topK));
    }

    /**
     * 同步 Agent。
     *
     * @param request        任务
     * @param http           身份
     * @param response       限流头
     * @param idempotencyKey 幂等键
     * @return 终答
     */
    @PostMapping("/agent")
    public ProductionAgentService.AgentAnswer agent(
        @Valid @RequestBody ChatRequestDTO request,
        HttpServletRequest http,
        HttpServletResponse response,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        requireAgent();
        if (metrics != null) {
            metrics.agentRun();
        }
        String bodyHash = hashBody(request);
        Optional<String> cached = beginIdempotency(principal, idempotencyKey, bodyHash);
        if (cached.isPresent()) {
            return jsonMapper.readValue(cached.get(), ProductionAgentService.AgentAnswer.class);
        }
        ProductionAgentService.AgentAnswer answer = agentService.run(
            principal, request.sessionId(), request.question(), request.provider());
        completeIdempotency(principal, idempotencyKey, bodyHash, answer);
        audit(principal, "agent", http, 200, null, request.question());
        return answer;
    }

    /**
     * 流式 Agent。
     *
     * @param question  任务
     * @param sessionId 会话
     * @param provider  模型
     * @param http      身份
     * @param response  头
     * @return SSE
     */
    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentStream(
        @RequestParam @NotBlank(message = "问题不能为空") String question,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String provider,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        requireAgent();
        if (metrics != null) {
            metrics.agentRun();
        }
        attachTrace(response);
        audit(principal, "agent.stream", http, 200, null, question);
        ProductionPrincipal frozen = principal;
        return runExecutor.start(writer ->
            agentService.stream(writer, frozen, sessionId, question, provider));
    }

    /**
     * 查看会话历史。
     *
     * @param sessionId 会话 id
     * @param http      身份
     * @return 按 seq 升序的消息列表
     */
    @GetMapping("/sessions/{sessionId}")
    public SessionVO session(@PathVariable String sessionId, HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        return new SessionVO(sessionId, chatService.history(principal, sessionId));
    }

    /**
     * 清空会话。
     *
     * @param sessionId 会话 id
     * @param http      身份
     * @return 是否存在
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ClearResult clearSession(@PathVariable String sessionId, HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        boolean existed = chatService.clearSession(principal, sessionId);
        audit(principal, "session.clear", http, 200, null, null);
        return new ClearResult(sessionId, existed);
    }

    /**
     * 断线续传。
     *
     * @param runId       run
     * @param lastEventId Last-Event-ID
     * @param http        身份
     * @return SSE
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
        @PathVariable String runId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
        HttpServletRequest http
    ) {
        principal(http);
        return runExecutor.resume(runId, lastEventId == null ? -1L : lastEventId);
    }

    private ProductionPrincipal rateLimit(HttpServletRequest http, HttpServletResponse response) {
        ProductionPrincipal principal = principal(http);
        if (bucket == null) {
            return principal;
        }
        try {
            int remaining = bucket.consume(
                "prod:rl:" + principal.tenantId() + ":" + principal.subject(), null);
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        } catch (RateLimitExceededException ex) {
            if (metrics != null) {
                metrics.rateLimited();
            }
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Remaining", "0");
            throw ex;
        }
        return principal;
    }

    private ProductionPrincipal principal(HttpServletRequest http) {
        if (http == null) {
            return new ProductionPrincipal("anonymous", "default", java.util.Set.of("USER"));
        }
        Object attr = http.getAttribute(ProductionPrincipal.ATTR);
        if (attr instanceof ProductionPrincipal principal) {
            return principal;
        }
        // standalone MockMvc 测试不走 JWT 过滤器
        return new ProductionPrincipal("anonymous", "default", java.util.Set.of("USER"));
    }

    private void requireAgent() {
        if (agentService == null) {
            throw new BusinessException(ErrorCodeEnum.AGENT_NOT_ASSEMBLED);
        }
    }

    private Optional<String> beginIdempotency(ProductionPrincipal principal, String key, String hash) {
        if (idempotency == null || key == null || key.isBlank()) {
            return Optional.empty();
        }
        return idempotency.begin(principal.tenantId(), key, hash);
    }

    private void completeIdempotency(
        ProductionPrincipal principal,
        String key,
        String hash,
        Object payload
    ) {
        if (idempotency == null || key == null || key.isBlank()) {
            return;
        }
        idempotency.complete(principal.tenantId(), key, hash, payload);
    }

    private String hashBody(Object body) {
        if (jsonMapper == null) {
            return "";
        }
        return IdempotencyManager.sha256(jsonMapper.writeValueAsString(body));
    }

    private void audit(
        ProductionPrincipal principal,
        String action,
        HttpServletRequest http,
        int status,
        String runId,
        String question
    ) {
        if (audit == null) {
            return;
        }
        String sha = question == null ? null : IdempotencyManager.sha256(question);
        String ip = http == null || http.getRemoteAddr() == null ? null : http.getRemoteAddr();
        audit.record(
            principal.tenantId(), principal.subject(), action,
            http == null ? "" : http.getServletPath(),
            status, runId, sha, null, ip
        );
    }

    private void attachTrace(HttpServletResponse response) {
        Tracer current = tracer;
        if (current == null) {
            return;
        }
        Span span = current.currentSpan();
        if (span != null) {
            String traceId = span.context().traceId();
            response.setHeader("traceparent", "00-" + traceId + "-" + span.context().spanId() + "-01");
            MDC.put("traceId", traceId);
        }
    }

    /**
     * 清空结果。
     *
     * @param sessionId 会话 id
     * @param existed   会话原先是否存在
     */
    public record ClearResult(String sessionId, boolean existed) {}
}
