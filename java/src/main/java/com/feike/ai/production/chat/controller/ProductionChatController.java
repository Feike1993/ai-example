package com.feike.ai.production.chat.controller;

import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.chat.model.ChatImage;
import com.feike.ai.production.chat.model.ChatRequestDTO;
import com.feike.ai.production.chat.model.LockProbeQuery;
import com.feike.ai.production.chat.model.LockProbeVO;
import com.feike.ai.production.chat.model.SessionVO;
import com.feike.ai.production.chat.service.ProductionChatService;

import com.feike.ai.production.agent.manager.ToolPolicy;
import com.feike.ai.production.agent.model.AgentTurnMedia;
import com.feike.ai.production.agent.model.AgentToolsVO;
import com.feike.ai.production.agent.model.ToolProbeQuery;
import com.feike.ai.production.agent.model.ToolProbeVO;
import com.feike.ai.production.agent.service.ProductionAgentService;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.media.manager.ProductionDocumentOcr;
import com.feike.ai.production.media.manager.ProductionImageDescribe;
import com.feike.ai.production.media.manager.ProductionMediaInspector;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.model.IngestJobVO;
import com.feike.ai.production.rag.ingest.service.ProductionIngestJobService;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.ratelimit.manager.IdempotencyManager;
import com.feike.ai.production.ratelimit.service.RateLimitExceededException;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.sse.service.SseRunExecutor;
import com.feike.ai.production.sse.service.SseStreamWriter;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.slf4j.MDC;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final ProductionInstanceIdentity instanceIdentity;//
    private final ProductionMediaInspector mediaInspector; //
    private final ProductionDocumentOcr documentOcr;
    private final ProductionImageDescribe imageDescribe;
    private final ProductionGuardrail guardrail;

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
            new ProductionInstanceIdentity("test"), null, null, null, null);
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
     * @param mediaInspector    带图 / 文档校验；测试可空
     * @param documentOcr       扫描 PDF 转写；测试可空
     * @param imageDescribe     Agent 看图转写；测试可空
     * @param guardrail         输入护栏；测试可空
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
        ProductionInstanceIdentity instanceIdentity,
        ProductionMediaInspector mediaInspector,
        ProductionDocumentOcr documentOcr,
        ProductionImageDescribe imageDescribe,
        ProductionGuardrail guardrail
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
        this.mediaInspector = mediaInspector;
        this.documentOcr = documentOcr;
        this.imageDescribe = imageDescribe;
        this.guardrail = guardrail;
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
            job = ingestJobService.submit(principal.tenantId());
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
        ProductionPrincipal principal = principal(http);
        if (ingestJobService == null) {
            throw new BusinessException(ErrorCodeEnum.INGEST_JOB_NOT_FOUND);
        }
        return ingestJobService.get(jobId, principal.tenantId());
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
        ProductionChatService.ChatAnswer answer;
        try {
            answer = chatService.answer(
                principal, request.sessionId(), request.question(), request.provider(), request.topK(),
                request.queryExpansion());
        } catch (RuntimeException ex) {
            abortIdempotency(principal, idempotencyKey);
            throw ex;
        }
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
        @RequestParam @NotBlank(message = "问题不能为空")
        @Size(max = ChatRequestDTO.MAX_QUESTION_CHARS, message = "问题过长") String question,
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
            chatService.streamAnswer(writer, frozen, sessionId, question, provider, topK, queryExpansion),
            null, frozen.tenantId());
    }

    /**
     * 纯文本流式问答。问句走请求体，避免进入 access log / 浏览器历史。
     *
     * @param request  问句
     * @param http     身份
     * @param response 限流头
     * @return SSE
     */
    @PostMapping(
        value = "/chat/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStreamJson(
        @Valid @RequestBody ChatRequestDTO request,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        return chatStream(
            request.question(), request.sessionId(), request.provider(), request.topK(),
            request.queryExpansion(), http, response);
    }

    /**
     * 图文 / 文档流式问答。SSE 契约与 GET 相同；{@code meta.hasImage} / {@code meta.imageCount}
     * 标明本轮是否带图，{@code meta.hasDocument} / {@code meta.documentCount} 标明文档。
     * <p>
     * 同名 {@code image}、{@code document} 可重复；有附件时先做 mime / 大小 / 件数 / 抽取（扫描 PDF 可选 OCR）
     * 再进生成，避免无 Key 时把坏文件打到网关。文档正文进 {@code UserMessage} 文本，不把 PDF/Office 当视觉 Media。
     *
     * @param question       问题
     * @param sessionId      会话
     * @param provider       模型
     * @param topK           topK
     * @param queryExpansion 查询扩展
     * @param image          可选同名多图
     * @param document       可选同名多文档
     * @param http           身份
     * @param response       限流头
     * @return SSE
     */
    @PostMapping(
        value = "/chat/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStreamPost(
        @RequestParam @NotBlank(message = "问题不能为空")
        @Size(max = ChatRequestDTO.MAX_QUESTION_CHARS, message = "问题过长") String question,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK,
        @RequestParam(required = false) String queryExpansion,
        @RequestParam(required = false) MultipartFile[] image,
        @RequestParam(required = false) MultipartFile[] document,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        PreparedAttachments prepared = prepareAttachments(image, document, question, provider, false);
        if (metrics != null) {
            metrics.chatRun();
        }
        attachTrace(response);
        audit(principal, "chat.stream", http, 200, null, question);
        ProductionPrincipal frozen = principal;
        List<ChatImage> frozenImages = prepared.images();
        List<ChatDocument> frozenDocs = prepared.documents();
        boolean frozenOcr = prepared.ocrUsed();
        return runExecutor.start(writer ->
            chatService.streamAnswer(
                writer, frozen, sessionId, question, provider, topK, queryExpansion,
                frozenImages, frozenDocs, frozenOcr),
            null, frozen.tenantId());
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
        ProductionAgentService.AgentAnswer answer;
        try {
            answer = agentService.run(
                principal, request.sessionId(), request.question(), request.provider());
        } catch (RuntimeException ex) {
            abortIdempotency(principal, idempotencyKey);
            throw ex;
        }
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
        @RequestParam @NotBlank(message = "问题不能为空")
        @Size(max = ChatRequestDTO.MAX_QUESTION_CHARS, message = "问题过长") String question,
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
                audit(frozen, "agent.done", http, auditStatus(writer), writer.runId(), question, durationMs, null, null);
            }
        }, runId -> audit(frozen, "agent.stream", http, 200, runId, question), frozen.tenantId());
    }

    /**
     * 纯文本流式 Agent。问句走请求体，避免进入 access log / 浏览器历史。
     *
     * @param request  任务
     * @param http     身份
     * @param response 头
     * @return SSE
     */
    @PostMapping(
        value = "/agent/stream",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter agentStreamJson(
        @Valid @RequestBody ChatRequestDTO request,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        return agentStream(request.question(), request.sessionId(), request.provider(), http, response);
    }

    /**
     * 带图 / 文档的流式 Agent。SSE 契约与 GET 相同。
     * <p>
     * 有图时先用现有 VL <strong>只转写/简述</strong>，再把正文交给文本工具循环。
     * 默认视觉模型不支持 Function Calling，不要把 VL 和 tools 揉成一轮。
     * 扫描 PDF 仍走文档 OCR；渲染页不进 Agent 识图 Media。
     *
     * @param question  任务
     * @param sessionId 会话
     * @param provider  模型
     * @param image     可选同名多图
     * @param document  可选同名多文档
     * @param http      身份
     * @param response  头
     * @return SSE
     */
    @PostMapping(
        value = "/agent/stream",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter agentStreamPost(
        @RequestParam @NotBlank(message = "问题不能为空")
        @Size(max = ChatRequestDTO.MAX_QUESTION_CHARS, message = "问题过长") String question,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) MultipartFile[] image,
        @RequestParam(required = false) MultipartFile[] document,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        PreparedAttachments prepared = prepareAttachments(image, document, question, provider, true);
        requireAgent();
        if (metrics != null) {
            metrics.agentRun();
        }
        attachTrace(response);
        ProductionPrincipal frozen = principal;
        AgentTurnMedia media = new AgentTurnMedia(
            prepared.documents(),
            prepared.imageTranscript(),
            prepared.imageCount(),
            prepared.ocrUsed(),
            prepared.visionTranscribed()
        );
        long startNanos = System.nanoTime();
        return runExecutor.start(writer -> {
            try {
                agentService.stream(writer, frozen, sessionId, question, provider, media);
            } finally {
                int durationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                audit(frozen, "agent.done", http, auditStatus(writer), writer.runId(), question, durationMs, null, null);
            }
        }, runId -> audit(frozen, "agent.stream", http, 200, runId, question), frozen.tenantId());
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
        String tool = ToolPolicy.normalize(query.tool());
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
        ProductionPrincipal principal = principal(http);
        return runExecutor.resume(runId, lastEventId == null ? -1L : lastEventId, principal.tenantId());
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
        }, null, principal.tenantId());
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
        return JwtAuthFilter.require(http);
    }

    /**
     * 是否有附件。
     * @param files 附件
     * @return 是否有附件
     */
    private static boolean hasParts(MultipartFile[] files) {
        if (files == null) {
            return false;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本轮附件进内存：mime / 件数 / 抽出 / 可选 OCR 或图片 VL 转写，再护栏。
     * <p>
     * 处理顺序刻意固定为“判断有效附件 → 媒体校验与内容抽取 → 必要时模型转写 →
     * 对最终文本统一执行输入护栏”。这样可以先在受控边界内拒绝超量、超大或类型不符的文件，
     * 再把文档抽取文本和图片转写文本纳入与用户问题相同的安全检查，避免附件内容绕过护栏。
     * <p>
     * 文档优先使用本地数字文本抽取，仅对标记为 {@link ChatDocument#ocrCandidate() OCR 候选}
     * 的扫描 PDF 调用视觉模型。OCR 返回后不再保留源文件字节，降低本轮后续处理的内存占用。
     * <p>
     * Agent 有图时 {@code transcribeImages=true}，在进 SSE 前完成转写，失败走 HTTP 422/503，
     * 不把位图带进工具循环。问答识图仍保留原图给视觉 ChatModel。
     *
     * @param image            图片附件；数组可空，空 part 会被忽略
     * @param document         文档附件；数组可空，空 part 会被忽略
     * @param question         用户问题，将与附件抽取出的文本一起接受输入护栏检查
     * @param provider         OCR 和图片转写使用的视觉模型提供者；为空时由模型工厂使用默认配置
     * @param transcribeImages 是否先把图片转成文本；{@code true} 用于 Agent，{@code false} 用于视觉问答
     * @return 已校验并完成必要转写的附件，以及 OCR、图片转写和原始图片数量元数据
     * @throws BusinessException 当媒体能力未装配、附件不合法、内容不可读或模型转写失败时
     */
    private PreparedAttachments prepareAttachments(
        MultipartFile[] image,
        MultipartFile[] document,
        String question,
        String provider,
        boolean transcribeImages
    ) {
        // 下游始终接收不可变空集合和空字符串而非 null，调用方无需为“无附件”另开分支。
        List<ChatImage> images = List.of();
        List<ChatDocument> documents = List.of();
        boolean ocrUsed = false;
        boolean visionTranscribed = false;
        String imageTranscript = "";

        // Spring 可能传入 null 数组、null 元素或空 part；只有实际有内容时才要求媒体组件存在，
        // 从而允许未装配媒体能力的部署继续处理纯文本请求。
        boolean hasImages = hasParts(image);
        boolean hasDocuments = hasParts(document);
        if (hasImages || hasDocuments) {
            if (mediaInspector == null) {
                throw new BusinessException(ErrorCodeEnum.MEDIA_UNSUPPORTED, "不支持的媒体类型");
            }
        }

        // 所有图片先统一执行数量、大小、MIME 与文件特征校验；后续模型只接触已通过边界检查的字节。
        if (hasImages) {
            images = mediaInspector.inspectImages(image);
        }
        if (hasDocuments) {
            // inspectDocuments 同时完成媒体校验和本地文本抽取；此阶段不直接调用视觉模型。
            documents = mediaInspector.inspectDocuments(document);
            if (needsOcr(documents)) {
                // 仅扫描 PDF 等本地抽取不足的候选需要 OCR；能力未装配时明确失败，
                // 不能把内容为空或不完整的文档静默交给后续模型。
                if (documentOcr == null) {
                    throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "无法读取文档内容");
                }
                // 批处理器会保留已成功数字抽取的文档，只转写候选文档，并在转写后清除源字节。
                ProductionDocumentOcr.OcrBatch batch = documentOcr.transcribeIfNeeded(documents, provider);
                documents = batch.documents();
                ocrUsed = batch.ocrUsed();
            }
        }

        // Agent 路径随后会丢弃图片，因此必须先保存原始张数，供响应、审计或指标描述本轮附件规模。
        int imageCount = images.size();
        if (transcribeImages && !images.isEmpty()) {
            // 工具循环使用文本模型，而默认视觉模型不保证支持 Function Calling；先把图片转成受控文本，
            // 再交给 Agent，可避免在同一轮同时传递位图和转写文本造成重复理解或能力不兼容。
            if (imageDescribe == null) {
                throw new BusinessException(ErrorCodeEnum.MEDIA_UNREADABLE, "无法识别图片内容");
            }
            ProductionImageDescribe.DescribeBatch batch = imageDescribe.describe(images, provider);
            imageTranscript = batch.transcript();
            visionTranscribed = batch.used();
            // 转写完成后位图不再参与 Agent 请求；清空既表达消费语义，也缩短大字节数组的存活链路。
            images = List.of();
        }

        if (guardrail != null) {
            // 护栏放在抽取和转写之后，确保用户原问题、文档正文和图片正文走同一安全边界；
            // 同时仍位于聊天或 Agent 调用之前，命中规则时不会启动后续 SSE 与模型执行。
            guardrail.checkInput(question);
            for (ChatDocument extracted : documents) {
                guardrail.checkInput(extracted.extractedText());
            }
            if (!imageTranscript.isBlank()) {
                guardrail.checkInput(imageTranscript);
            }
        }
        return new PreparedAttachments(
            images, documents, ocrUsed, visionTranscribed, imageTranscript, imageCount);
    }

    private static boolean needsOcr(List<ChatDocument> documents) {
        for (ChatDocument document : documents) {
            if (document.ocrCandidate()) {
                return true;
            }
        }
        return false;
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

    private void abortIdempotency(ProductionPrincipal principal, String key) {
        if (idempotency == null || key == null || key.isBlank()) {
            return;
        }
        idempotency.abort(principal.tenantId(), key);
    }

    private static int auditStatus(SseStreamWriter writer) {
        RunStateEnum state = writer.terminalState();
        if (state == RunStateEnum.DONE) {
            return 200;
        }
        if (state == RunStateEnum.CANCELLED) {
            return 499;
        }
        if (state == RunStateEnum.ERROR) {
            return 500;
        }
        return 200;
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

    /**
     * 准备附件。
     * @param images             问答识图字节；Agent 转写后为空
     * @param documents          抽出文档
     * @param ocrUsed            文档是否 OCR
     * @param visionTranscribed  是否 VL 转写过图片
     * @param imageTranscript    图片转写正文
     * @param imageCount         原图张数
     */
    private record PreparedAttachments(
        List<ChatImage> images,
        List<ChatDocument> documents,
        boolean ocrUsed,
        boolean visionTranscribed,
        String imageTranscript,
        int imageCount
    ) {}
}
