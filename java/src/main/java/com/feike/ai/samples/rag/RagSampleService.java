package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 样例：内置 Markdown → 分块 Embedding 写入 pgvector，再检索拼上下文生成。
 * <p>
 * 支持纯向量与 Hybrid（向量 + PostgreSQL 全文 + RRF）两种检索模式。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagSampleService {

    private static final Logger log = LoggerFactory.getLogger(RagSampleService.class);

    /** 元数据键：用于幂等重建时按语料过滤删除。 */
    public static final String META_CORPUS = "corpus";
    public static final String CORPUS_DEMO = "ai-example-demo";

    /** 空检索且跳过 LLM 时的固定拒答文案。 */
    public static final String EMPTY_REFUSAL =
        "根据当前知识库的检索结果，没有找到与问题相关的内容，因此无法回答。"
            + "请换个问法，或先确认已 ingest 相关文档。";

    private static final String SYSTEM_GROUNDED = """
        你是助手。只根据「检索上下文」回答用户问题；上下文不足或为空时明确说不知道，不要编造。
        回答用简体中文。
        """;

    /** 检索模式：纯向量或 Hybrid RRF。 */
    public enum RetrievalMode {
        /** 仅 pgvector 相似度 */
        vector,
        /** 向量 + PG 全文 + RRF */
        hybrid
    }

    /** 查询扩展：无 / 改写短句 / HyDE 假想文档。 */
    public enum QueryExpansion {
        none,
        rewrite,
        hyde
    }

    private final VectorStore vectorStore;
    private final LlmProviderRegistry registry;
    private final AiProperties.Rag ragSettings;
    private final TokenTextSplitter splitter;
    // 关键词检索辅助
    private final RagKeywordRetriever keywordRetriever;

    /**
     * @param vectorStore       pgvector
     * @param registry          Chat / Embedding
     * @param ragSettings       topK / chunkSize / 空检索 / Hybrid
     * @param keywordRetriever  关键词路；测试可传 {@code null}（Hybrid 回退向量）
     */
    public RagSampleService(
        VectorStore vectorStore,
        LlmProviderRegistry registry,
        AiProperties.Rag ragSettings,
        RagKeywordRetriever keywordRetriever
    ) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.ragSettings = ragSettings;
        this.keywordRetriever = keywordRetriever;
        this.splitter = TokenTextSplitter.builder()
            .withChunkSize(ragSettings.chunkSize())
            .build();
    }

    /**
     * 幂等重建演示索引：删掉本语料旧 chunk，再从 classpath 样例文档写入。
     *
     * @return 写入的 chunk 数与来源文件名
     */
    public IngestResult ingest() {
        try {
            vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(META_CORPUS),
                new Filter.Value(CORPUS_DEMO)
            ));
        } catch (Exception ex) {
            log.debug("清理旧索引时忽略: {}", ex.toString());
        }

        List<Document> sourceDocs = loadClasspathDocs();
        List<Document> chunks = splitter.apply(sourceDocs);
        for (Document chunk : chunks) {
            chunk.getMetadata().putIfAbsent(META_CORPUS, CORPUS_DEMO);
        }
        vectorStore.add(chunks);
        if (keywordRetriever != null) {
            keywordRetriever.ensureFullTextIndex();
        }
        List<String> sources = sourceDocs.stream()
            .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
            .distinct()
            .sorted()
            .toList();
        log.info("RAG ingest 完成: chunks={}, sources={}", chunks.size(), sources);
        return new IngestResult(chunks.size(), sources);
    }

    /**
     * 检索 + 同步生成（默认纯向量，与二期行为一致）。
     */
    public RagQueryResult query(String question, String provider, Integer topK) {
        return query(question, provider, topK, RetrievalMode.vector, null, null);
    }

    /**
     * 检索 + 同步生成，可指定检索模式与查询改写。
     */
    public RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery
    ) {
        return query(question, provider, topK, retrievalMode, rewriteQuery, null);
    }

    /**
     * 检索 + 同步生成；支持 {@code queryExpansion}（优先于 rewriteQuery 布尔）。
     *
     * @param queryExpansion none / rewrite / hyde；空则由 rewriteQuery 推导
     */
    public RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion
    ) {
        QueryExpansion expansion = resolveExpansion(queryExpansion, rewriteQuery);
        RetrievalBundle bundle = retrieveExpanded(question, topK, retrievalMode, expansion, provider);
        return answerFromHits(question, provider, bundle, retrievalMode);
    }

    /**
     * 同一问题并排返回 vector 与 hybrid 两套结果，便于对照。
     */
    public CompareResult queryCompare(String question, String provider, Integer topK, Boolean rewriteQuery) {
        RagQueryResult vector = query(question, provider, topK, RetrievalMode.vector, rewriteQuery, null);
        RagQueryResult hybrid = query(question, provider, topK, RetrievalMode.hybrid, rewriteQuery, null);
        return new CompareResult(vector, hybrid);
    }

    /**
     * 同一问题对照 none / rewrite / hyde 三套检索命中（默认不三次生成，控成本）。
     */
    public ExpansionCompareResult queryCompareExpansion(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode
    ) {
        RetrievalMode mode = retrievalMode == null ? RetrievalMode.vector : retrievalMode;
        RetrievalBundle none = retrieveExpanded(question, topK, mode, QueryExpansion.none, provider);
        RetrievalBundle rewrite = retrieveExpanded(question, topK, mode, QueryExpansion.rewrite, provider);
        RetrievalBundle hyde = retrieveExpanded(question, topK, mode, QueryExpansion.hyde, provider);
        return new ExpansionCompareResult(
            toExpansionView(none),
            toExpansionView(rewrite),
            toExpansionView(hyde)
        );
    }

    /**
     * 流式 RAG 回答（先检索再 SSE）。
     */
    public Flux<String> queryStream(String question, String provider, Integer topK) {
        return queryStream(question, provider, topK, RetrievalMode.vector, null);
    }

    /**
     * 流式 RAG，可指定检索模式与查询扩展。
     */
    public Flux<String> queryStream(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery
    ) {
        return queryStream(question, provider, topK, retrievalMode, rewriteQuery, null);
    }

    /**
     * 流式 RAG；支持 {@code queryExpansion}。
     */
    public Flux<String> queryStream(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion
    ) {
        QueryExpansion expansion = resolveExpansion(queryExpansion, rewriteQuery);
        List<Document> hits = retrieveExpanded(question, topK, retrievalMode, expansion, provider).hits();
        return streamAnswer(question, provider, hits);
    }

    /**
     * 在已检索结果上做流式生成。
     */
    public Flux<String> streamAnswer(String question, String provider, List<Document> hits) {
        if (isRetrievalEmpty(hits) && ragSettings.skipLlmWhenEmpty()) {
            log.info("RAG 流式空检索短路拒答: question={}, hits={}", question, hits.size());
            return Flux.just(EMPTY_REFUSAL);
        }
        String context = buildContext(hits);
        return registry.plainClient(provider)
            .prompt()
            .system(SYSTEM_GROUNDED)
            .user("检索上下文：\n" + context + "\n\n用户问题：" + question)
            .stream()
            .content();
    }

    /**
     * 检索 topK 文档（默认纯向量，供流式接口等复用）。
     */
    public List<Document> retrieve(String question, Integer topK) {
        return retrieve(question, topK, RetrievalMode.vector, null, null);
    }

    /**
     * 按模式检索：Hybrid 时走向量 + 关键词 + RRF；支持 rewriteQuery 兼容布尔。
     */
    public List<Document> retrieve(
        String question,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery,
        String provider
    ) {
        QueryExpansion expansion = resolveExpansion(null, rewriteQuery);
        return retrieveExpanded(question, topK, retrievalMode, expansion, provider).hits();
    }

    public boolean isRetrievalEmpty(List<Document> hits) {
        int size = hits == null ? 0 : hits.size();
        return size < ragSettings.minSources();
    }

    public List<SourceView> toSources(List<Document> hits) {
        List<SourceView> views = new ArrayList<>();
        if (hits == null) {
            return views;
        }
        for (Document doc : hits) {
            String text = doc.getText() == null ? "" : doc.getText();
            String excerpt = text.length() > 160 ? text.substring(0, 160) + "…" : text;
            Map<String, Object> metadata = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
            views.add(new SourceView(
                doc.getId(),
                String.valueOf(metadata.getOrDefault("source", "")),
                excerpt,
                metadata,
                intOrNull(metadata.get("vectorRank")),
                intOrNull(metadata.get("keywordRank")),
                doubleOrNull(metadata.get("rrfScore"))
            ));
        }
        return views;
    }

    /**
     * 解析查询扩展：显式 queryExpansion 优先；否则 rewriteQuery=true → rewrite。
     */
    static QueryExpansion resolveExpansion(String queryExpansion, Boolean rewriteQuery) {
        if (queryExpansion != null && !queryExpansion.isBlank()) {
            String value = queryExpansion.trim().toLowerCase();
            if ("hyde".equals(value)) {
                return QueryExpansion.hyde;
            }
            if ("rewrite".equals(value)) {
                return QueryExpansion.rewrite;
            }
            return QueryExpansion.none;
        }
        if (Boolean.TRUE.equals(rewriteQuery)) {
            return QueryExpansion.rewrite;
        }
        return QueryExpansion.none;
    }

    /**
     * 按扩展策略检索；假想文档仅作检索查询，不入 sources。
     */
    RetrievalBundle retrieveExpanded(
        String question,
        Integer topK,
        RetrievalMode retrievalMode,
        QueryExpansion expansion,
        String provider
    ) {
        RetrievalMode mode = retrievalMode == null ? RetrievalMode.vector : retrievalMode;
        QueryExpansion effective = expansion == null ? QueryExpansion.none : expansion;
        int k = topK != null && topK > 0 ? topK : ragSettings.topK();

        if (effective == QueryExpansion.hyde) {
            if (!ragSettings.hyde().enabled()) {
                log.warn("HyDE 已关闭，回退为 none");
                List<Document> hits = retrieveByMode(question, k, mode);
                return new RetrievalBundle(hits, QueryExpansion.none, null, null);
            }
            String hypo = generateHypotheticalDocument(question, provider);
            List<Document> hydeHits = vectorRetrieve(hypo, k);
            List<Document> hits;
            if (ragSettings.hyde().fuseWithOriginal()) {
                List<Document> originalHits = vectorRetrieve(question, k);
                // 假想路 → vectorRank；原问题路 → keywordRank（字段复用，便于观测）
                hits = fuseDocumentLists(hydeHits, originalHits, k);
            } else {
                hits = hydeHits;
            }
            if (mode == RetrievalMode.hybrid && ragSettings.hybrid().enabled() && keywordRetriever != null) {
                hits = fuseWithKeyword(hits, question, k);
            }
            return new RetrievalBundle(hits, QueryExpansion.hyde, hypo, null);
        }

        if (effective == QueryExpansion.rewrite) {
            String rewritten = rewriteQueryAlways(question, provider);
            List<Document> hits = retrieveByMode(rewritten, k, mode);
            return new RetrievalBundle(hits, QueryExpansion.rewrite, null, rewritten);
        }

        // none：仍尊重 hybrid.rewriteQueryEnabled 全局开关（第四期行为）
        String effectiveQuestion = maybeRewriteQuery(question, provider, false);
        String rewritten = effectiveQuestion.equals(question) ? null : effectiveQuestion;
        List<Document> hits = retrieveByMode(effectiveQuestion, k, mode);
        return new RetrievalBundle(
            hits,
            rewritten == null ? QueryExpansion.none : QueryExpansion.rewrite,
            null,
            rewritten
        );
    }

    private List<Document> retrieveByMode(String query, int k, RetrievalMode mode) {
        if (mode == RetrievalMode.hybrid
            && ragSettings.hybrid().enabled()
            && keywordRetriever != null) {
            return hybridRetrieve(query, k);
        }
        return vectorRetrieve(query, k);
    }

    private RagQueryResult answerFromHits(
        String question,
        String provider,
        RetrievalBundle bundle,
        RetrievalMode retrievalMode
    ) {
        List<Document> hits = bundle.hits();
        boolean empty = isRetrievalEmpty(hits);
        if (empty && ragSettings.skipLlmWhenEmpty()) {
            log.info("RAG 空检索短路拒答: question={}, hits={}, mode={}, expansion={}",
                question, hits.size(), retrievalMode, bundle.expansion());
            return new RagQueryResult(
                EMPTY_REFUSAL,
                toSources(hits),
                true,
                null,
                retrievalMode.name(),
                bundle.expansion().name(),
                bundle.hypotheticalDocument()
            );
        }
        String context = buildContext(hits);
        var call = registry.plainClient(provider)
            .prompt()
            .system(SYSTEM_GROUNDED)
            .user("检索上下文：\n" + context + "\n\n用户问题：" + question)
            .call();
        return new RagQueryResult(
            call.content(),
            toSources(hits),
            empty,
            TokenUsageExtractor.from(call.chatResponse()),
            retrievalMode.name(),
            bundle.expansion().name(),
            bundle.hypotheticalDocument()
        );
    }

    private ExpansionView toExpansionView(RetrievalBundle bundle) {
        return new ExpansionView(
            bundle.expansion().name(),
            toSources(bundle.hits()),
            isRetrievalEmpty(bundle.hits()),
            bundle.hypotheticalDocument(),
            bundle.rewrittenQuery()
        );
    }

    private String generateHypotheticalDocument(String question, String provider) {
        try {
            String hypo = registry.plainClient(provider)
                .prompt()
                .system("""
                    根据用户问题写一段可能出现在技术知识库中的假想回答段落。
                    用陈述句、百科风格；不要编造库外专有产品细节；只输出段落正文，不要解释。
                    """)
                .user(question)
                .call()
                .content();
            if (hypo != null && !hypo.isBlank()) {
                return hypo.trim();
            }
        } catch (Exception ex) {
            log.warn("HyDE 假想文档生成失败，回退原问题检索: {}", ex.toString());
        }
        return question;
    }

    private String rewriteQueryAlways(String question, String provider) {
        if (question == null || question.isBlank()) {
            return question;
        }
        try {
            String rewritten = registry.plainClient(provider)
                .prompt()
                .system("把用户问题改写成适合检索的短句，保留关键实体与术语，只输出一行，不要解释。")
                .user(question)
                .call()
                .content();
            if (rewritten != null && !rewritten.isBlank()) {
                log.debug("RAG 查询改写: {} -> {}", question, rewritten.trim());
                return rewritten.trim();
            }
        } catch (Exception ex) {
            log.warn("查询改写失败，使用原问题: {}", ex.toString());
        }
        return question;
    }

    /**
     * 两路文档列表 RRF 融合（复用 {@link RrfFusion}；第二路排名写入 keywordRank）。
     */
    private List<Document> fuseDocumentLists(List<Document> first, List<Document> second, int k) {
        List<String> firstIds = first.stream().map(Document::getId).toList();
        List<String> secondIds = second.stream().map(Document::getId).toList();
        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            firstIds,
            secondIds,
            ragSettings.hybrid().rrfK(),
            k
        );
        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document doc : first) {
            byId.put(doc.getId(), doc);
        }
        for (Document doc : second) {
            byId.putIfAbsent(doc.getId(), doc);
        }
        return materializeFused(fused, byId);
    }

    private List<Document> fuseWithKeyword(List<Document> primary, String keywordQuery, int k) {
        int keywordK = ragSettings.hybrid().keywordTopK();
        List<Document> keywordHits = keywordRetriever.search(keywordQuery, keywordK, CORPUS_DEMO);
        return fuseDocumentLists(primary, keywordHits, k);
    }

    private static List<Document> materializeFused(List<RrfFusion.RankedId> fused, Map<String, Document> byId) {
        List<Document> merged = new ArrayList<>();
        for (RrfFusion.RankedId ranked : fused) {
            Document doc = byId.get(ranked.id());
            if (doc == null) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>(doc.getMetadata());
            if (ranked.vectorRank() != null) {
                meta.put("vectorRank", ranked.vectorRank());
            }
            if (ranked.keywordRank() != null) {
                meta.put("keywordRank", ranked.keywordRank());
            }
            meta.put("rrfScore", ranked.rrfScore());
            merged.add(Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(meta)
                .build());
        }
        return merged;
    }

    private List<Document> vectorRetrieve(String question, int k) {
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(k)
                .filterExpression(META_CORPUS + " == '" + CORPUS_DEMO + "'") // 过滤语料来源
                .build()
        );
    }

    /**
     * 混合检索：向量 + 关键词 + RRF。
     * 什么是 RRF：RRF 是一种检索结果融合（fusion）方法，通过将向量检索和关键词检索的结果进行融合，以提高检索效果。
     * @param question 查询问题
     * @param k 检索结果数量
     * @return 检索结果
     */
    private List<Document> hybridRetrieve(String question, int k) {
        // 向量检索
        List<Document> vectorHits = vectorRetrieve(question, k);
        // 关键词检索
        int keywordK = ragSettings.hybrid().keywordTopK();
        List<Document> keywordHits = keywordRetriever.search(question, keywordK, CORPUS_DEMO);

        List<String> vectorIds = vectorHits.stream().map(Document::getId).toList();
        List<String> keywordIds = keywordHits.stream().map(Document::getId).toList();
        // RRF 融合
        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            vectorIds,
            keywordIds,
            ragSettings.hybrid().rrfK(),
            k
        );

        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document doc : vectorHits) {
            byId.put(doc.getId(), doc);
        }
        for (Document doc : keywordHits) {
            byId.putIfAbsent(doc.getId(), doc);
        }
        return materializeFused(fused, byId);
    }

    private String maybeRewriteQuery(String question, String provider, Boolean rewriteQuery) {
        boolean enabled = Boolean.TRUE.equals(rewriteQuery) || ragSettings.hybrid().rewriteQueryEnabled();
        if (!enabled || question == null || question.isBlank()) {
            return question;
        }
        try {
            String rewritten = registry.plainClient(provider)
                .prompt()
                .system("把用户问题改写成适合检索的短句，保留关键实体与术语，只输出一行，不要解释。")
                .user(question)
                .call()
                .content();
            if (rewritten != null && !rewritten.isBlank()) {
                log.debug("RAG 查询改写: {} -> {}", question, rewritten.trim());
                return rewritten.trim();
            }
        } catch (Exception ex) {
            log.warn("查询改写失败，使用原问题: {}", ex.toString());
        }
        return question;
    }

    private static String buildContext(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return "（无检索结果）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Document doc = hits.get(i);
            sb.append("[").append(i + 1).append("] source=")
                .append(doc.getMetadata().getOrDefault("source", "?"))
                .append("\n")
                .append(doc.getText())
                .append("\n\n");
        }
        return sb.toString();
    }

    private List<Document> loadClasspathDocs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:rag-docs/*.md");
            if (resources.length == 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "未找到 classpath:rag-docs/*.md");
            }
            List<Document> docs = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename() == null ? "unknown.md" : resource.getFilename();
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                docs.add(Document.builder()
                    .text(text)
                    .metadata(Map.of(
                        "source", filename,
                        META_CORPUS, CORPUS_DEMO
                    ))
                    .build());
            }
            return docs;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取样例文档失败: " + ex.getMessage());
        }
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static Double doubleOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    public record IngestResult(int chunkCount, List<String> sources) {}

    /**
     * 一次检索的中间结果（含扩展策略与假想文档预览）。
     */
    record RetrievalBundle(
        List<Document> hits,
        QueryExpansion expansion,
        String hypotheticalDocument,
        String rewrittenQuery
    ) {}

    /**
     * @param retrievalMode          实际使用的检索模式：{@code vector} 或 {@code hybrid}
     * @param queryExpansion         none / rewrite / hyde
     * @param hypotheticalDocument   HyDE 生成的假想段落（仅预览；不在 sources 中）
     */
    public record RagQueryResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        TokenUsage usage,
        String retrievalMode,
        String queryExpansion,
        String hypotheticalDocument
    ) {
        /** 二期兼容：无 retrievalMode 字段时视为 vector。 */
        public RagQueryResult(String answer, List<SourceView> sources, boolean retrievalEmpty, TokenUsage usage) {
            this(answer, sources, retrievalEmpty, usage, RetrievalMode.vector.name(), QueryExpansion.none.name(), null);
        }

        /** 第四期兼容：无 queryExpansion 字段。 */
        public RagQueryResult(
            String answer,
            List<SourceView> sources,
            boolean retrievalEmpty,
            TokenUsage usage,
            String retrievalMode
        ) {
            this(answer, sources, retrievalEmpty, usage, retrievalMode, QueryExpansion.none.name(), null);
        }
    }

    public record CompareResult(RagQueryResult vector, RagQueryResult hybrid) {}

    /** 查询扩展对照中的单路视图（默认只含检索命中，不含 answer）。 */
    public record ExpansionView(
        String queryExpansion,
        List<SourceView> sources,
        boolean retrievalEmpty,
        String hypotheticalDocument,
        String rewrittenQuery
    ) {}

    public record ExpansionCompareResult(ExpansionView none, ExpansionView rewrite, ExpansionView hyde) {}

    public record SourceView(
        String id,
        String source,
        String excerpt,
        Map<String, Object> metadata,
        Integer vectorRank,
        Integer keywordRank,
        Double rrfScore
    ) {
        /** 二期兼容：无 rank 字段。 */
        public SourceView(String id, String source, String excerpt, Map<String, Object> metadata) {
            this(id, source, excerpt, metadata, null, null, null);
        }
    }
}
