package com.feike.ai.samples.rag.service;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.core.rag.CitedSource;
import com.feike.ai.samples.rag.model.ChunkingStrategyEnum;
import com.feike.ai.samples.rag.model.CitationModeEnum;
import com.feike.ai.samples.rag.model.QueryExpansionEnum;
import com.feike.ai.samples.rag.model.RetrievalModeEnum;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * RAG 样例业务门面。
 */
public interface RagSampleService {

    String META_CORPUS = "corpus";
    String CORPUS_DEMO = "ai-example-demo";
    String META_CHUNKING = "chunking";
    String EMPTY_REFUSAL = "根据当前知识库的检索结果，没有找到与问题相关的内容，因此无法回答。请换个问法，或先确认已 ingest 相关文档。";
    String CITATION_REFUSAL = "模型给出的引用未通过校验（空引用或 sourceId 不在本次检索结果中），因此无法采信该答案。请重试，或改用 citationMode=none。";

    record SourceView(
        String id,
        String source,
        String excerpt,
        Map<String, Object> metadata,
        Integer vectorRank,
        Integer keywordRank,
        Double rrfScore,
        String chunkRole,
        String parentExcerpt
    ) implements CitedSource {
        /** 记忆先验等短构造：无 rank / 父子字段。 */
        public SourceView(String id, String source, String excerpt, Map<String, Object> metadata) {
            this(id, source, excerpt, metadata, null, null, null, null, null);
        }
    }

    record CitationView(String sourceId, String quote) {}

    record IngestResult(int chunkCount, List<String> sources, String strategy, Map<String, Integer> corpora) {
        /** 二期兼容：仅 chunkCount + sources。 */
        public IngestResult(int chunkCount, List<String> sources) {
            this(chunkCount, sources, ChunkingStrategyEnum.TOKEN.getCode(), Map.of(CORPUS_DEMO, chunkCount));
        }
    }

    record RagQueryResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        TokenUsageDTO usage,
        String retrievalMode,
        String queryExpansion,
        String hypotheticalDocument,
        String chunkingStrategy,
        String citationMode,
        List<CitationView> citations,
        Boolean citationValid,
        String rewrittenQuery,
        List<SourceView> memoryHints
    ) {
        /** 生成后补 rewrittenQuery / memoryHints 前的短构造。 */
        public RagQueryResult(
            String answer,
            List<SourceView> sources,
            boolean retrievalEmpty,
            TokenUsageDTO usage,
            String retrievalMode,
            String queryExpansion,
            String hypotheticalDocument,
            String chunkingStrategy,
            String citationMode,
            List<CitationView> citations,
            Boolean citationValid
        ) {
            this(
                answer, sources, retrievalEmpty, usage, retrievalMode, queryExpansion,
                hypotheticalDocument, chunkingStrategy, citationMode, citations, citationValid, null, List.of()
            );
        }
    }

    record CompareResult(RagQueryResult vector, RagQueryResult hybrid) {}

    record ExpansionView(
        String queryExpansion,
        List<SourceView> sources,
        boolean retrievalEmpty,
        String hypotheticalDocument,
        String rewrittenQuery,
        List<SourceView> memoryHints
    ) {}

    record ExpansionCompareResult(ExpansionView none, ExpansionView rewrite, ExpansionView hyde) {}

    record MemoryRewriteCompareResult(ExpansionView none, ExpansionView rewrite, ExpansionView memoryRewrite) {}

    record MemoryPathView(String answer, List<SourceView> sources, boolean retrievalEmpty, String userId, TokenUsageDTO usage) {}

    record MemoryRagCompareResult(RagQueryResult rag, MemoryPathView memory, boolean generateAnswers) {}

    record ChunkingView(String chunkingStrategy, String corpus, List<SourceView> sources, boolean retrievalEmpty) {}

    record ChunkingCompareResult(ChunkingView token, ChunkingView semantic, ChunkingView parentChild) {}

    /**
     * 一次检索的中间结果（含扩展策略与假想文档预览）。
     *
     * @param memoryHints 记忆先验；仅 memory_rewrite 非空，不进 RAG sources
     */
    record RetrievalBundle(
        List<Document> hits,
        QueryExpansionEnum expansion,
        String hypotheticalDocument,
        String rewrittenQuery,
        List<SourceView> memoryHints
    ) {
        RetrievalBundle(
            List<Document> hits,
            QueryExpansionEnum expansion,
            String hypotheticalDocument,
            String rewrittenQuery
        ) {
            this(hits, expansion, hypotheticalDocument, rewrittenQuery, List.of());
        }
    }

    IngestResult ingest();

    IngestResult ingest(String strategy);

    RagQueryResult query(String question, String provider, Integer topK);

    RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery
    );

    RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion
    );

    RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion,
        String chunkingStrategy
    );

    RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion,
        String chunkingStrategy,
        String citationMode
    );

    RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion,
        String chunkingStrategy,
        String citationMode,
        String userId,
        Integer memoryTopK
    );

    CompareResult queryCompare(String question, String provider, Integer topK, Boolean rewriteQuery);

    ExpansionCompareResult queryCompareExpansion(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode
    );

    MemoryRewriteCompareResult queryCompareMemoryRewrite(
        String question,
        String provider,
        Integer topK,
        String userId,
        Integer memoryTopK
    );

    MemoryRagCompareResult queryCompareMemory(
        String question,
        String provider,
        Integer topK,
        String userId,
        Integer memoryTopK,
        Boolean generateAnswers
    );

    ChunkingCompareResult queryCompareChunking(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode
    );

    Flux<String> queryStream(String question, String provider, Integer topK);

    Flux<String> queryStream(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery
    );

    Flux<String> queryStream(
        String question,
        String provider,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion
    );

    Flux<String> streamAnswer(String question, String provider, List<Document> hits);

    List<Document> retrieve(String question, Integer topK);

    boolean isRetrievalEmpty(List<Document> hits);

    List<SourceView> toSources(List<Document> hits);

    List<SourceView> toSources(List<Document> hits, int maxExcerpt);

    String semanticCorpusPublic();

    String parentCorpusPublic();

    RetrievalBundle retrieveExpanded(
        String question,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        QueryExpansionEnum expansion,
        String provider,
        String corpus
    );

    RetrievalBundle retrieveExpanded(
        String question,
        Integer topK,
        RetrievalModeEnum retrievalMode,
        QueryExpansionEnum expansion,
        String provider,
        String corpus,
        String userId,
        Integer memoryTopK
    );

    /**
     * 解析查询扩展：显式 queryExpansion 优先；否则 rewriteQuery=true → rewrite。
     */
    static QueryExpansionEnum resolveExpansion(String queryExpansion, Boolean rewriteQuery) {
        if (queryExpansion != null && !queryExpansion.isBlank()) {
            return QueryExpansionEnum.from(queryExpansion);
        }
        if (Boolean.TRUE.equals(rewriteQuery)) {
            return QueryExpansionEnum.REWRITE;
        }
        return QueryExpansionEnum.NONE;
    }

    /**
     * 解析分块策略。
     */
    static ChunkingStrategyEnum parseChunkingStrategy(String value) {
        return ChunkingStrategyEnum.from(value);
    }
}
