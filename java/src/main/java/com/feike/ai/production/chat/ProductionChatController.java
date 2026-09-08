package com.feike.ai.production.chat;

import com.feike.ai.production.rag.ingest.ProductionIngestService;
import com.feike.ai.production.sse.SseRunExecutor;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 工业级链路 HTTP 入口，统一挂在 {@code /api/v1/**}。
 * <p>
 * 与样例路径（{@code /rag}、{@code /chat}、{@code /agent}）完全不重叠，
 * 于是网关上可以对这一段单独配鉴权、限流和监控，而不牵连教学接口。
 * <p>
 * 本类走组件扫描并自带开关条件，而不是在配置类里 {@code @Bean} 注册：Spring MVC 7 起
 * 只认 {@code @Controller} 派生的 Bean 为处理器，光有 {@code @RequestMapping} 不会建立路由映射。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionChatController {

    private final ProductionChatService chatService;
    private final ProductionIngestService ingestService;
    private final SseRunExecutor runExecutor;

    /**
     * @param chatService   编排层
     * @param ingestService 入库层
     * @param runExecutor   SSE 生命周期管家
     */
    public ProductionChatController(
        ProductionChatService chatService,
        ProductionIngestService ingestService,
        SseRunExecutor runExecutor
    ) {
        this.chatService = chatService;
        this.ingestService = ingestService;
        this.runExecutor = runExecutor;
    }

    /**
     * 幂等重建生产语料索引。
     *
     * @return 入库结果
     */
    @PostMapping("/rag/ingest")
    public ProductionIngestService.IngestResult ingest() {
        return ingestService.ingest();
    }

    /**
     * 同步问答。
     *
     * @param request 请求体
     * @return 答案与来源
     */
    @PostMapping("/chat")
    public ProductionChatService.ChatAnswer chat(@RequestBody ChatRequest request) {
        return chatService.answer(request.question(), request.provider(), request.topK());
    }

    /**
     * 流式问答，返回严格契约的 SSE。
     *
     * @param question 用户问题
     * @param provider Chat Provider id
     * @param topK     覆盖默认 topK
     * @return SSE 流
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
        @RequestParam @NotBlank String question,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK
    ) {
        return runExecutor.start(writer -> chatService.streamAnswer(writer, question, provider, topK));
    }

    /**
     * 断线续传：补齐 {@code Last-Event-ID} 之后的事件，run 未结束时继续跟随。
     *
     * @param runId       原 run 标识，取自首条 meta 事件
     * @param lastEventId 已收到的最大 seq；缺省表示从头回放
     * @return SSE 流
     */
    @GetMapping(value = "/runs/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(
        @PathVariable String runId,
        @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId
    ) {
        return runExecutor.resume(runId, lastEventId == null ? -1L : lastEventId);
    }

    /**
     * 问答请求体。
     *
     * @param question 用户问题
     * @param provider Chat Provider id；空则用默认
     * @param topK     覆盖默认 topK
     */
    public record ChatRequest(@NotBlank String question, String provider, Integer topK) {}
}
