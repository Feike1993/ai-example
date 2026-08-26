package com.feike.ai.samples.rag;

import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * RAG 样例 HTTP：ingest / query / SSE。
 */
@Validated
@RestController
@RequestMapping("/rag")
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSampleController {

    /**
     * @param question 用户问题
     * @param provider 可选 Chat Provider
     * @param topK     可选覆盖默认 topK
     */
    public record RagQueryRequest(@NotBlank String question, String provider, Integer topK) {}

    private final RagSampleService ragSampleService;
    private final JsonMapper jsonMapper;

    /**
     * @param ragSampleService 检索与生成
     * @param jsonMapper       序列化 sources（Spring Boot 4 自动配置的 Jackson 3）
     */
    public RagSampleController(RagSampleService ragSampleService, JsonMapper jsonMapper) {
        this.ragSampleService = ragSampleService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 幂等重建演示语料索引。
     *
     * @return chunk 数与源文件
     */
    @PostMapping("/ingest")
    public RagSampleService.IngestResult ingest() {
        return ragSampleService.ingest();
    }

    /**
     * 检索 + 同步生成。
     *
     * @param request 问题与 Provider
     * @return 答案与 sources
     */
    @PostMapping("/query")
    public RagSampleService.RagQueryResult query(@RequestBody @Validated RagQueryRequest request) {
        return ragSampleService.query(request.question(), request.provider(), request.topK());
    }

    /**
     * 检索后流式生成。
     * <p>
     * {@code event:sources} 推送命中摘要，后续默认 message 为文本增量。
     *
     * @param question 问题
     * @param provider Chat Provider
     * @param topK     可选
     * @return SSE 事件流
     */
    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(
        @RequestParam @NotBlank String question,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK
    ) {
        List<Document> hits = ragSampleService.retrieve(question, topK);
        List<RagSampleService.SourceView> sources = ragSampleService.toSources(hits);
        String sourcesJson;
        try {
            sourcesJson = jsonMapper.writeValueAsString(Map.of("sources", sources));
        } catch (Exception ex) {
            sourcesJson = "{\"sources\":[]}";
        }
        ServerSentEvent<String> sourcesEvent = ServerSentEvent.<String>builder()
            .event("sources")
            .data(sourcesJson)
            .build();
        Flux<ServerSentEvent<String>> answerEvents = ragSampleService
            .streamAnswer(question, provider, hits)
            .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
        return Flux.concat(Flux.just(sourcesEvent), answerEvents);
    }
}
