package com.feike.ai.samples.rag.controller;

import com.feike.ai.samples.rag.model.ChunkingStrategyEnum;
import com.feike.ai.samples.rag.model.QueryExpansionEnum;
import com.feike.ai.samples.rag.model.RagQuery;
import com.feike.ai.samples.rag.model.RetrievalModeEnum;
import com.feike.ai.samples.rag.service.RagSampleService;

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
 * RAG 样例 HTTP：ingest / query / compare / compare-expansion / compare-chunking / SSE。
 */
@Validated
@RestController
@RequestMapping("/rag")
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSampleController {

    public record RagIngestRequest(String strategy) {}

    private final RagSampleService ragSampleService;
    private final JsonMapper jsonMapper;

    public RagSampleController(RagSampleService ragSampleService, JsonMapper jsonMapper) {
        this.ragSampleService = ragSampleService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/ingest")
    public RagSampleService.IngestResult ingest(@RequestBody(required = false) RagIngestRequest request) {
        String strategy = request == null ? null : request.strategy();
        return ragSampleService.ingest(strategy);
    }

    @PostMapping("/query")
    public RagSampleService.RagQueryResult query(@RequestBody @Validated RagQuery request) {
        return ragSampleService.query(
            request.question(),
            request.provider(),
            request.topK(),
            parseMode(request.retrievalMode()),
            request.rewriteQuery(),
            request.queryExpansion(),
            request.chunkingStrategy(),
            request.citationMode(),
            request.userId(),
            request.memoryTopK()
        );
    }

    @PostMapping("/query/compare")
    public RagSampleService.CompareResult queryCompare(@RequestBody @Validated RagQuery request) {
        return ragSampleService.queryCompare(
            request.question(),
            request.provider(),
            request.topK(),
            request.rewriteQuery()
        );
    }

    @PostMapping("/query/compare-expansion")
    public RagSampleService.ExpansionCompareResult queryCompareExpansion(
        @RequestBody @Validated RagQuery request
    ) {
        return ragSampleService.queryCompareExpansion(
            request.question(),
            request.provider(),
            request.topK(),
            parseMode(request.retrievalMode())
        );
    }

    /**
     * none / rewrite / memory_rewrite 三路检索对照（默认只比 sources）。
     */
    @PostMapping("/query/compare-memory-rewrite")
    public RagSampleService.MemoryRewriteCompareResult queryCompareMemoryRewrite(
        @RequestBody @Validated RagQuery request
    ) {
        return ragSampleService.queryCompareMemoryRewrite(
            request.question(),
            request.provider(),
            request.topK(),
            request.userId(),
            request.memoryTopK()
        );
    }

    /**
     * RAG vs 记忆双路对照。
     */
    @PostMapping("/query/compare-memory")
    public RagSampleService.MemoryRagCompareResult queryCompareMemory(
        @RequestBody @Validated RagQuery request
    ) {
        return ragSampleService.queryCompareMemory(
            request.question(),
            request.provider(),
            request.topK(),
            request.userId(),
            request.memoryTopK(),
            request.generateAnswers()
        );
    }

    /**
     * 对照 token vs semantic 两套检索命中（默认不三次生成）。
     */
    @PostMapping("/query/compare-chunking")
    public RagSampleService.ChunkingCompareResult queryCompareChunking(
        @RequestBody @Validated RagQuery request
    ) {
        return ragSampleService.queryCompareChunking(
            request.question(),
            request.provider(),
            request.topK(),
            parseMode(request.retrievalMode())
        );
    }

    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> queryStream(
        @RequestParam @NotBlank String question,
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) Integer topK,
        @RequestParam(required = false) String retrievalMode,
        @RequestParam(required = false) Boolean rewriteQuery,
        @RequestParam(required = false) String queryExpansion,
        @RequestParam(required = false) String chunkingStrategy
    ) {
        QueryExpansionEnum expansion =
            RagSampleService.resolveExpansion(queryExpansion, rewriteQuery);
        ChunkingStrategyEnum chunking =
            RagSampleService.parseChunkingStrategy(chunkingStrategy);
        String corpus = switch (chunking) {
            case SEMANTIC -> ragSampleService.semanticCorpusPublic();
            case PARENT_CHILD -> ragSampleService.parentCorpusPublic();
            default -> RagSampleService.CORPUS_DEMO;
        };
        RagSampleService.RetrievalBundle bundle = ragSampleService.retrieveExpanded(
            question,
            topK,
            parseMode(retrievalMode),
            expansion,
            provider,
            corpus
        );
        List<Document> hits = bundle.hits();
        List<RagSampleService.SourceView> sources = ragSampleService.toSources(hits);
        boolean retrievalEmpty = ragSampleService.isRetrievalEmpty(hits);
        String sourcesJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sources", sources);
            payload.put("retrievalEmpty", retrievalEmpty);
            payload.put("retrievalMode", parseMode(retrievalMode).getCode());
            payload.put("queryExpansion", bundle.expansion().getCode());
            payload.put("chunkingStrategy", chunking.getCode());
            if (bundle.hypotheticalDocument() != null) {
                payload.put("hypotheticalDocument", bundle.hypotheticalDocument());
            }
            sourcesJson = jsonMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            sourcesJson = "{\"sources\":[],\"retrievalEmpty\":true,\"retrievalMode\":\"vector\",\"queryExpansion\":\"none\"}";
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

    private static RetrievalModeEnum parseMode(String mode) {
        if (mode != null && mode.equalsIgnoreCase("hybrid")) {
            return RetrievalModeEnum.HYBRID;
        }
        return RetrievalModeEnum.VECTOR;
    }
}
