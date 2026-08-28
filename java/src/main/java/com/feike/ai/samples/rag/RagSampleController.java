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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 样例 HTTP：ingest / query / compare / SSE。
 */
@Validated
@RestController
@RequestMapping("/rag")
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSampleController {

    /**
     * @param question        用户问题
     * @param provider        可选 Chat Provider
     * @param topK            可选覆盖默认 topK
     * @param retrievalMode   {@code vector}（默认）或 {@code hybrid}
     * @param rewriteQuery    是否改写问题后再检索
     */
    public record RagQueryRequest(
        @NotBlank String question,
        String provider,
        Integer topK,
        String retrievalMode,
        Boolean rewriteQuery
    ) {}

    private final RagSampleService ragSampleService;
    private final JsonMapper jsonMapper;

    public RagSampleController(RagSampleService ragSampleService, JsonMapper jsonMapper) {
        this.ragSampleService = ragSampleService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/ingest")
    public RagSampleService.IngestResult ingest() {
        return ragSampleService.ingest();
    }

    @PostMapping("/query")
    public RagSampleService.RagQueryResult query(@RequestBody @Validated RagQueryRequest request) {
        return ragSampleService.query(
            request.question(),
            request.provider(),
            request.topK(),
            parseMode(request.retrievalMode()),
            request.rewriteQuery()
        );
    }

    /**
     * 同一问题返回 vector 与 hybrid 两套 sources + answer，便于 playground 并排对照。
     */
    @PostMapping("/query/compare")
    public RagSampleService.CompareResult queryCompare(@RequestBody @Validated RagQueryRequest request) {
        return ragSampleService.queryCompare(
            request.question(),
            request.provider(),
            request.topK(),
            request.rewriteQuery()
        );
    }

    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(
        @RequestParam @NotBlank String question,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK,
        @RequestParam(required = false) String retrievalMode,
        @RequestParam(required = false) Boolean rewriteQuery
    ) {
        List<Document> hits = ragSampleService.retrieve(
            question,
            topK,
            parseMode(retrievalMode),
            rewriteQuery,
            provider
        );
        List<RagSampleService.SourceView> sources = ragSampleService.toSources(hits);
        boolean retrievalEmpty = ragSampleService.isRetrievalEmpty(hits);
        String sourcesJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sources", sources);
            payload.put("retrievalEmpty", retrievalEmpty);
            payload.put("retrievalMode", parseMode(retrievalMode).name());
            sourcesJson = jsonMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            sourcesJson = "{\"sources\":[],\"retrievalEmpty\":true,\"retrievalMode\":\"vector\"}";
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

    private static RagSampleService.RetrievalMode parseMode(String mode) {
        if (mode != null && mode.equalsIgnoreCase("hybrid")) {
            return RagSampleService.RetrievalMode.hybrid;
        }
        return RagSampleService.RetrievalMode.vector;
    }
}
