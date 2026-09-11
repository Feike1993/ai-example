package com.feike.ai.production.chat.controller;

import com.feike.ai.production.chat.model.ChatRequestDTO;
import com.feike.ai.production.chat.model.LockProbeQuery;
import com.feike.ai.production.chat.model.LockProbeVO;
import com.feike.ai.production.chat.model.SessionVO;
import com.feike.ai.production.chat.service.ProductionChatService;

import com.feike.ai.production.agent.manager.ToolPolicy;
import com.feike.ai.production.agent.model.AgentToolsVO;
import com.feike.ai.production.agent.model.ToolProbeQuery;
import com.feike.ai.production.agent.model.ToolProbeVO;
import com.feike.ai.production.agent.service.ProductionAgentService;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.model.IngestJobVO;
import com.feike.ai.production.rag.ingest.service.ProductionIngestJobService;
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
import org.springframework.http.ResponseEntity;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
    private final ProductionIngestJobService ingestJobService;
    private final SseRunExecutor runExecutor;
    private final ProductionAgentService agentService;
    private final RedisTokenBucket bucket;
    private final IdempotencyManager idempotency;
    private final AuditService audit;
    private final ProductionMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Tracer tracer;
    private final ProductionInstanceIdentity instanceIdentity;

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
        this(chatService, ingestService, null, runExecutor, null, null, null, null, null, null, null,
            new ProductionInstanceIdentity("test"));
    }

    /**
     * @param chatService       问答
     * @param ingestService     同步入库（Agent rebuild_index）
     * @param ingestJobService  异步入库；测试可空
     * @param runExecutor       SSE
     * @param agentService      Agent
     * @param bucket            限流
     * @param idempotency       幂等
     * @param audit             审计
     * @param metrics           指标
     * @param jsonMapper        幂等哈希
     * @param tracer            可选 Trace
     * @param instanceIdentity  本进程短名
     */
    @Autowired
    public ProductionChatController(
        ProductionChatService chatService,
        ProductionIngestService ingestService,
        ProductionIngestJobService ingestJobService,
        SseRunExecutor runExecutor,
        ProductionAgentService agentService,
        RedisTokenBucket bucket,
        IdempotencyManager idempotency,
        AuditService audit,
        ProductionMetrics metrics,
        JsonMapper jsonMapper,
        ObjectProvider<Tracer> tracer,
        ProductionInstanceIdentity instanceIdentity
    ) {
        this.chatService = chatService;
        this.ingestService = ingestService;
        this.ingestJobService = ingestJobService;
        this.runExecutor = runExecutor;
        this.agentService = agentService;
        this.bucket = bucket;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.tracer = tracer == null ? null : tracer.getIfAvailable();
        this.instanceIdentity = instanceIdentity == null
            ? new ProductionInstanceIdentity("test")
            : instanceIdentity;
    }

    /**
     * 投递幂等重建生产语料索引。仅 ADMIN。立即 202，建索引在后台（Redis Stream / 本进程队列）。
     *
     * @param http HTTP
     * @return 入库任务
     */
    @PostMapping("/rag/ingest")
    public ResponseEntity<IngestJobVO> ingest(HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        if (!principal.admin()) {
            throw new BusinessException(ErrorCodeEnum.INGEST_FORBIDDEN);
        }
        IngestJobVO job;
        if (ingestJobService != null) {
            job = ingestJobService.submit();
        } else {
            ProductionIngestService.IngestResult result = ingestService.ingest();
            job = new IngestJobVO(
                "sync",
                "succeeded",
                result.corpus(),
                result.chunkCount(),
                result.sources(),
                null,
                instanceIdentity.id()
            );
        }
        audit(principal, "rag.ingest", http, 202, job.jobId(), null);
        return ResponseEntity.accepted().body(job);
    }

    /**
     * 查询入库任务。
     *
     * @param jobId 任务 id
     * @param http  身份
     * @return 任务视图
     */
    @GetMapping("/rag/ingest/jobs/{jobId}")
    public IngestJobVO ingestJob(@PathVariable String jobId, HttpServletRequest http) {
        principal(http);
        if (ingestJobService == null) {
            throw new BusinessException(ErrorCodeEnum.INGEST_JOB_NOT_FOUND);
        }
        return ingestJobService.get(jobId);
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
            principal, request.sessionId(), request.question(), request.provider(), request.topK(),
            request.queryExpansion());
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
     * @param question        问题
     * @param sessionId       会话
     * @param provider        模型
     * @param topK            topK
     * @param queryExpansion  none / rewrite / hyde
     * @param http            身份
     * @param response        限流头
     * @return SSE
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
        @RequestParam @NotBlank(message = "问题不能为空") String question,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK,
        @RequestParam(required = false) String queryExpansion,
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
            chatService.streamAnswer(writer, frozen, sessionId, question, provider, topK, queryExpansion));
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
        long startNanos = System.nanoTime();
        ProductionAgentService.AgentAnswer answer = agentService.run(
            principal, request.sessionId(), request.question(), request.provider());
        completeIdempotency(principal, idempotencyKey, bodyHash, answer);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        audit(principal, "agent", http, 200, answer.runId(), request.question());
        audit(principal, "agent.done", http, 200, answer.runId(), request.question(),
            (int) durationMs, null, null);
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
        ProductionPrincipal frozen = principal;
        long startNanos = System.nanoTime();
        return runExecutor.start(writer -> {
            try {
                agentService.stream(writer, frozen, sessionId, question, provider);
            } finally {
                int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                audit(frozen, "agent.done", http, 200, writer.runId(), question, durationMs, null, null);
            }
        }, runId -> audit(frozen, "agent.stream", http, 200, runId, question));
    }

    /**
     * 当前身份允许的工具。不调模型。
     *
     * @param http 身份
     * @return 工具名
     */
    @GetMapping("/agent/tools")
    public AgentToolsVO agentTools(HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        List<String> tools = new ArrayList<>(ToolPolicy.allowed(principal));
        tools.sort(String::compareTo);
        return new AgentToolsVO(List.copyOf(tools));
    }

    /**
     * 只走 {@link ToolPolicy}，不调模型、不执行工具。
     *
     * @param query 工具名
     * @param http  身份
     * @return 是否允许
     */
    @PostMapping("/agent/tool-probe")
    public ToolProbeVO toolProbe(@Valid @RequestBody ToolProbeQuery query, HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        String tool = query.tool().trim().toLowerCase(Locale.ROOT);
        boolean allowed = ToolPolicy.allows(principal, tool);
        if (!allowed && metrics != null) {
            metrics.toolDenied();
        }
        audit(principal, "agent.tool", http, 200, null, tool, null, tool, !allowed);
        return new ToolProbeVO(tool, allowed, !allowed);
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

    /**
     * 占用会话锁一段时间。给多实例演示用，不调 LLM。
     *
     * @param sessionId 会话
     * @param query     持锁时长
     * @param http      身份
     * @return 持锁结果，含 instanceId
     */
    @PostMapping("/sessions/{sessionId}/lock-probe")
    public LockProbeVO lockProbe(
        @PathVariable String sessionId,
        @RequestBody(required = false) LockProbeQuery query,
        HttpServletRequest http
    ) {
        ProductionPrincipal principal = principal(http);
        Integer holdMs = query == null ? null : query.holdMs();
        ProductionChatService.LockHold hold = chatService.probeLock(principal, sessionId, holdMs);
        return new LockProbeVO(hold.sessionId(), instanceIdentity.id(), hold.heldMs());
    }

    /**
     * 写几条假 SSE 事件后结束。给跨实例 Last-Event-ID 续传演示用，不调 LLM。
     *
     * @param http 身份
     * @return SSE
     */
    @GetMapping(value = "/sse-probe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sseProbe(HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        String instance = instanceIdentity.id();
        return runExecutor.start(writer -> {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("instanceId", instance);
            meta.put("probe", true);
            meta.put("tenant", principal.tenantId());
            writer.meta(meta);
            writer.delta("ha-probe");
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("instanceId", instance);
            writer.done(done);
        });
    }

    /**
     * 限流。
     *
     * @param http      身份
     * @param response  限流头
     * @return 身份
     */
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
        audit(principal, action, http, status, runId, question, null, null, null);
    }

    private void audit(
        ProductionPrincipal principal,
        String action,
        HttpServletRequest http,
        int status,
        String runId,
        String question,
        Integer durationMs,
        String toolName,
        Boolean denied
    ) {
        if (audit == null) {
            return;
        }
        String sha = question == null ? null : IdempotencyManager.sha256(question);
        String ip = http == null || http.getRemoteAddr() == null ? null : http.getRemoteAddr();
        audit.record(
            principal.tenantId(), principal.subject(), action,
            http == null ? "" : http.getServletPath(),
            status, runId, sha, durationMs, ip, toolName, denied
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
