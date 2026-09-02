package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.structured.StructuredOutputInvoker;
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
import java.util.Set;

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

    /** 元数据键：分块策略标记。 */
    public static final String META_CHUNKING = "chunking";

    /** 空检索且跳过 LLM 时的固定拒答文案。 */
    public static final String EMPTY_REFUSAL =
        "根据当前知识库的检索结果，没有找到与问题相关的内容，因此无法回答。"
            + "请换个问法，或先确认已 ingest 相关文档。";

    /** citation 校验失败时的固定拒答文案。 */
    public static final String CITATION_REFUSAL =
        "模型给出的引用未通过校验（空引用或 sourceId 不在本次检索结果中），因此无法采信该答案。"
            + "请重试，或改用 citationMode=none。";

    private static final String SYSTEM_GROUNDED = """
        你是助手。只根据「检索上下文」回答用户问题；上下文不足或为空时明确说不知道，不要编造。
        回答用简体中文。
        """;

    private static final String SYSTEM_CITED = """
        你是助手。只根据「检索上下文」回答用户问题；上下文不足时明确说不知道，不要编造。
        必须返回 JSON：answer（简体中文答案）与 citations（数组，每项含 sourceId、quote）。
        sourceId 必须是检索上下文中给出的文档 id；每条关键主张至少对应一条 citation。
        不要输出 Markdown 代码块。
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

    /** 分块策略：固定 token、结构语义、或父子文档（7b）。 */
    public enum ChunkingStrategy {
        token,
        semantic,
        parent_child
    }

    /** 引用模式：自由文本或强制结构化 citation。 */
    public enum CitationMode {
        none,
        required
    }

    private final VectorStore vectorStore;
    private final LlmProviderRegistry registry;
    private final AiProperties.Rag ragSettings;
    private final TokenTextSplitter splitter;
    private final SemanticMarkdownSplitter semanticSplitter;
    private final RagKeywordRetriever keywordRetriever;
    private final StructuredOutputInvoker structuredOutputInvoker;

    /**
     * @param vectorStore              pgvector
     * @param registry                 Chat / Embedding
     * @param ragSettings              topK / chunkSize / 空检索 / Hybrid / chunking
     * @param keywordRetriever         关键词路；测试可传 {@code null}（Hybrid 回退向量）
     * @param structuredOutputInvoker  citationMode=required 时使用；测试可传 {@code null}
     */
    public RagSampleService(
        VectorStore vectorStore,
        LlmProviderRegistry registry,
        AiProperties.Rag ragSettings,
        RagKeywordRetriever keywordRetriever,
        StructuredOutputInvoker structuredOutputInvoker
    ) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.ragSettings = ragSettings;
        this.keywordRetriever = keywordRetriever;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.splitter = TokenTextSplitter.builder()
            .withChunkSize(ragSettings.chunkSize())
            .build();
        this.semanticSplitter = new SemanticMarkdownSplitter(ragSettings.chunkSize());
    }

    /**
     * 测试兼容：无 structured invoker。
     */
    public RagSampleService(
        VectorStore vectorStore,
        LlmProviderRegistry registry,
        AiProperties.Rag ragSettings,
        RagKeywordRetriever keywordRetriever
    ) {
        this(vectorStore, registry, ragSettings, keywordRetriever, null);
    }

    /** 幂等重建演示索引（默认 token corpus，与二期一致）。 */
    public IngestResult ingest() {
        return ingest("token");
    }

    /**
     * 按策略重建索引：{@code token} / {@code semantic} / {@code all}。
     *
     * @param strategy 分块策略；空则 token
     */
    public IngestResult ingest(String strategy) {
        String value = strategy == null || strategy.isBlank() ? "token" : strategy.trim().toLowerCase();
        if ("all".equals(value)) {
            IngestResult token = ingestOne(ChunkingStrategy.token);
            IngestResult semantic = ingestOne(ChunkingStrategy.semantic);
            IngestResult parent = ingestOne(ChunkingStrategy.parent_child);
            List<String> sources = new ArrayList<>(token.sources());
            for (String s : semantic.sources()) {
                if (!sources.contains(s)) {
                    sources.add(s);
                }
            }
            for (String s : parent.sources()) {
                if (!sources.contains(s)) {
                    sources.add(s);
                }
            }
            sources = sources.stream().sorted().toList();
            Map<String, Integer> corpora = new LinkedHashMap<>();
            corpora.put(CORPUS_DEMO, token.chunkCount());
            corpora.put(semanticCorpus(), semantic.chunkCount());
            corpora.put(parentCorpus(), parent.chunkCount());
            return new IngestResult(
                token.chunkCount() + semantic.chunkCount() + parent.chunkCount(),
                sources,
                "all",
                corpora
            );
        }
        return ingestOne(parseChunkingStrategy(value));
    }

    /**
     * 按策略重建单套索引：先按 corpus 删旧再写入，避免与另一套分块语料互相覆盖。
     */
    private IngestResult ingestOne(ChunkingStrategy strategy) {
        String corpus = corpusFor(strategy);
        try {
            vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(META_CORPUS),
                new Filter.Value(corpus)
            ));
        } catch (Exception ex) {
            log.debug("清理旧索引时忽略: {}", ex.toString());
        }

        List<Document> sourceDocs = loadClasspathDocs();
        // token / semantic / parent_child → 不同切分，写入各自 corpus
        List<Document> chunks;
        if (strategy == ChunkingStrategy.parent_child) {
            chunks = buildParentChildChunks(sourceDocs, corpus);
        } else if (strategy == ChunkingStrategy.semantic) {
            chunks = semanticSplitter.apply(sourceDocs);
            for (Document chunk : chunks) {
                chunk.getMetadata().put(META_CORPUS, corpus);
                chunk.getMetadata().put(META_CHUNKING, strategy.name());
            }
        } else {
            chunks = splitter.apply(sourceDocs);
            for (Document chunk : chunks) {
                chunk.getMetadata().put(META_CORPUS, corpus);
                chunk.getMetadata().put(META_CHUNKING, strategy.name());
            }
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
        log.info("RAG ingest 完成: strategy={}, corpus={}, chunks={}, sources={}",
            strategy, corpus, chunks.size(), sources);
        return new IngestResult(chunks.size(), sources, strategy.name(), Map.of(corpus, chunks.size()));
    }

    /**
     * 父块（语义切）→ 子块硬切；只入库子块，parentText 写入 metadata。
     * <p>
     * 刻意不入父向量：检索粒度用短子块，生成上下文再展开父全文。
     */
    private List<Document> buildParentChildChunks(List<Document> sourceDocs, String corpus) {
        // 父块复用语义切分，保留标题/段落边界，避免父段从章节中间切开
        List<Document> parents = semanticSplitter.apply(sourceDocs);
        List<Document> children = new ArrayList<>();
        int parentSeq = 0;
        int childSize = ragSettings.chunking().childSize();
        for (Document parent : parents) {
            parentSeq++;
            String source = String.valueOf(parent.getMetadata().getOrDefault("source", "unknown"));
            String parentText = parent.getText() == null ? "" : parent.getText();
            // 序号+来源构成稳定键，供 expandParents 按父去重
            String parentId = "p-" + parentSeq + "-" + source;
            // 子块按 childSize 硬切（句号/换行优先），提高命中精度
            List<String> parts = SemanticMarkdownSplitter.hardSplit(parentText, childSize);
            if (parts.isEmpty()) {
                // 空父段不产出子块，避免写入空向量
                continue;
            }
            int chunkIndex = 0;
            for (String part : parts) {
                Map<String, Object> meta = new LinkedHashMap<>();
                if (parent.getMetadata() != null) {
                    meta.putAll(parent.getMetadata());
                }
                meta.put(META_CORPUS, corpus);
                meta.put(META_CHUNKING, ChunkingStrategy.parent_child.name());
                meta.put("chunkRole", "child");
                meta.put("parentId", parentId);
                // 每子块复制整段父文：展开无需二次查库（演示取舍，非生产最优）
                meta.put("parentText", parentText);
                meta.put("chunkIndex", chunkIndex++);
                meta.put("source", source);
                children.add(Document.builder()
                    .text(part)
                    .metadata(meta)
                    .build());
            }
        }
        // 仅返回子块列表：父块本身不 Embedding、不入 VectorStore
        return children;
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
        return query(question, provider, topK, retrievalMode, rewriteQuery, queryExpansion, null);
    }

    /**
     * 检索 + 同步生成；可指定分块语料（token / semantic）。
     *
     * @param chunkingStrategy token（默认）或 semantic
     */
    public RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion,
        String chunkingStrategy
    ) {
        return query(question, provider, topK, retrievalMode, rewriteQuery, queryExpansion, chunkingStrategy, null);
    }

    /**
     * 检索 + 同步生成；可指定 citationMode。
     *
     * @param citationMode {@code none}（默认）或 {@code required}
     */
    public RagQueryResult query(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode,
        Boolean rewriteQuery,
        String queryExpansion,
        String chunkingStrategy,
        String citationMode
    ) {
        ChunkingStrategy chunking = parseChunkingStrategy(chunkingStrategy);
        QueryExpansion expansion = resolveExpansion(queryExpansion, rewriteQuery);
        CitationMode citations = parseCitationMode(citationMode);
        RetrievalBundle bundle = retrieveExpanded(
            question, topK, retrievalMode, expansion, provider, corpusFor(chunking)
        );
        return answerFromHits(question, provider, bundle, retrievalMode, chunking, citations);
    }

    /**
     * 同一问题并排返回 vector 与 hybrid 两套结果，便于对照。
     */
    public CompareResult queryCompare(String question, String provider, Integer topK, Boolean rewriteQuery) {
        RagQueryResult vector = query(question, provider, topK, RetrievalMode.vector, rewriteQuery, null, null);
        RagQueryResult hybrid = query(question, provider, topK, RetrievalMode.hybrid, rewriteQuery, null, null);
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
        RetrievalBundle none = retrieveExpanded(question, topK, mode, QueryExpansion.none, provider, CORPUS_DEMO);
        RetrievalBundle rewrite = retrieveExpanded(question, topK, mode, QueryExpansion.rewrite, provider, CORPUS_DEMO);
        RetrievalBundle hyde = retrieveExpanded(question, topK, mode, QueryExpansion.hyde, provider, CORPUS_DEMO);
        return new ExpansionCompareResult(
            toExpansionView(none),
            toExpansionView(rewrite),
            toExpansionView(hyde)
        );
    }

    /**
     * 对照 token vs semantic 两套检索命中。
     * <p>
     * 只比 sources（与空检索标志），默认不跑双重 LLM，降低对照成本。
     */
    public ChunkingCompareResult queryCompareChunking(
        String question,
        String provider,
        Integer topK,
        RetrievalMode retrievalMode
    ) {
        RetrievalMode mode = retrievalMode == null ? RetrievalMode.vector : retrievalMode;
        RetrievalBundle token = retrieveExpanded(
            question, topK, mode, QueryExpansion.none, provider, corpusFor(ChunkingStrategy.token)
        );
        RetrievalBundle semantic = retrieveExpanded(
            question, topK, mode, QueryExpansion.none, provider, corpusFor(ChunkingStrategy.semantic)
        );
        RetrievalBundle parentChild = retrieveExpanded(
            question, topK, mode, QueryExpansion.none, provider, corpusFor(ChunkingStrategy.parent_child)
        );
        return new ChunkingCompareResult(
            toChunkingView(ChunkingStrategy.token, token),
            toChunkingView(ChunkingStrategy.semantic, semantic),
            toChunkingView(ChunkingStrategy.parent_child, parentChild)
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
        List<Document> hits = retrieveExpanded(
            question, topK, retrievalMode, expansion, provider, CORPUS_DEMO
        ).hits();
        return streamAnswer(question, provider, hits);
    }

    /**
     * 在已检索结果上做流式生成。
     * <p>
     * 生成前同样走 {@link #expandParents}，与同步路径一致。
     */
    public Flux<String> streamAnswer(String question, String provider, List<Document> hits) {
        // 流式与同步一致：上下文展开父块，空检索判定仍看原始 hits
        List<Document> contextDocs = expandParents(hits);
        if (isRetrievalEmpty(hits) && ragSettings.skipLlmWhenEmpty()) {
            log.info("RAG 流式空检索短路拒答: question={}, hits={}", question, hits.size());
            return Flux.just(EMPTY_REFUSAL);
        }
        String context = buildContext(contextDocs);
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
        return retrieveExpanded(question, topK, retrievalMode, expansion, provider, CORPUS_DEMO).hits();
    }

    public boolean isRetrievalEmpty(List<Document> hits) {
        int size = hits == null ? 0 : hits.size();
        return size < ragSettings.minSources();
    }

    /** 默认摘录 160 字；对照分块时请用 {@link #toSources(List, int)} 传 0 取全文。 */
    public List<SourceView> toSources(List<Document> hits) {
        return toSources(hits, 160);
    }

    /**
     * 将命中文档转为 SourceView。
     *
     * @param hits       检索命中
     * @param maxExcerpt 最大摘录长度；{@code <= 0} 表示返回完整 chunk 正文（分块对照需要看全量差异）
     */
    public List<SourceView> toSources(List<Document> hits, int maxExcerpt) {
        List<SourceView> views = new ArrayList<>();
        if (hits == null) {
            return views;
        }
        for (Document doc : hits) {
            String text = doc.getText() == null ? "" : doc.getText();
            String excerpt = (maxExcerpt > 0 && text.length() > maxExcerpt)
                ? text.substring(0, maxExcerpt) + "…"
                : text;
            Map<String, Object> metadata = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
            // UI 展示命中子块；parentExcerpt 另给父文预览，与 LLM 用的全文区分开
            views.add(new SourceView(
                doc.getId(),
                String.valueOf(metadata.getOrDefault("source", "")),
                excerpt,
                metadata,
                intOrNull(metadata.get("vectorRank")),
                intOrNull(metadata.get("keywordRank")),
                doubleOrNull(metadata.get("rrfScore")),
                metadata.get("chunkRole") == null ? null : String.valueOf(metadata.get("chunkRole")),
                parentExcerpt(metadata.get("parentText"))
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
        String provider,
        String corpus
    ) {
        RetrievalMode mode = retrievalMode == null ? RetrievalMode.vector : retrievalMode;
        QueryExpansion effective = expansion == null ? QueryExpansion.none : expansion;
        int k = topK != null && topK > 0 ? topK : ragSettings.topK();
        String effectiveCorpus = (corpus == null || corpus.isBlank()) ? CORPUS_DEMO : corpus;

        if (effective == QueryExpansion.hyde) {
            if (!ragSettings.hyde().enabled()) {
                log.warn("HyDE 已关闭，回退为 none");
                List<Document> hits = retrieveByMode(question, k, mode, effectiveCorpus);
                return new RetrievalBundle(hits, QueryExpansion.none, null, null);
            }
            String hypo = generateHypotheticalDocument(question, provider);
            List<Document> hydeHits = vectorRetrieve(hypo, k, effectiveCorpus);
            List<Document> hits;
            // 是否融合原始检索结果
            if (ragSettings.hyde().fuseWithOriginal()) {
                List<Document> originalHits = vectorRetrieve(question, k, effectiveCorpus);
                hits = fuseDocumentLists(hydeHits, originalHits, k);
            } else {
                hits = hydeHits;
            }
            // 是否进行关键词检索融合
            if (mode == RetrievalMode.hybrid && ragSettings.hybrid().enabled() && keywordRetriever != null) {
                hits = fuseWithKeyword(hits, question, k, effectiveCorpus);
            }
            return new RetrievalBundle(hits, QueryExpansion.hyde, hypo, null);
        }

        if (effective == QueryExpansion.rewrite) {
            String rewritten = rewriteQueryAlways(question, provider);
            List<Document> hits = retrieveByMode(rewritten, k, mode, effectiveCorpus);
            return new RetrievalBundle(hits, QueryExpansion.rewrite, null, rewritten);
        }

        String effectiveQuestion = maybeRewriteQuery(question, provider, false);
        String rewritten = effectiveQuestion.equals(question) ? null : effectiveQuestion;
        List<Document> hits = retrieveByMode(effectiveQuestion, k, mode, effectiveCorpus);
        return new RetrievalBundle(
            hits,
            rewritten == null ? QueryExpansion.none : QueryExpansion.rewrite,
            null,
            rewritten
        );
    }

    private List<Document> retrieveByMode(String query, int k, RetrievalMode mode, String corpus) {
        if (mode == RetrievalMode.hybrid
            && ragSettings.hybrid().enabled()
            && keywordRetriever != null) {
            return hybridRetrieve(query, k, corpus);
        }
        return vectorRetrieve(query, k, corpus);
    }

    private RagQueryResult answerFromHits(
        String question,
        String provider,
        RetrievalBundle bundle,
        RetrievalMode retrievalMode,
        ChunkingStrategy chunking
    ) {
        return answerFromHits(question, provider, bundle, retrievalMode, chunking, CitationMode.none);
    }

    private RagQueryResult answerFromHits(
        String question,
        String provider,
        RetrievalBundle bundle,
        RetrievalMode retrievalMode,
        ChunkingStrategy chunking,
        CitationMode citationMode
    ) {
        List<Document> hits = bundle.hits();
        // 生成上下文：父子策略下展开为父全文；sources 仍用原始 hits
        List<Document> contextDocs = expandParents(hits);
        boolean empty = isRetrievalEmpty(hits);
        String chunkingName = chunking == null ? ChunkingStrategy.token.name() : chunking.name();
        CitationMode mode = citationMode == null ? CitationMode.none : citationMode;
        List<SourceView> sources = toSources(hits);
        if (empty && ragSettings.skipLlmWhenEmpty()) {
            log.info("RAG 空检索短路拒答: question={}, hits={}, mode={}, expansion={}, chunking={}",
                question, hits.size(), retrievalMode, bundle.expansion(), chunkingName);
            return new RagQueryResult(
                EMPTY_REFUSAL,
                sources,
                true,
                null,
                retrievalMode.name(),
                bundle.expansion().name(),
                bundle.hypotheticalDocument(),
                chunkingName,
                mode.name(),
                List.of(),
                mode == CitationMode.required ? Boolean.FALSE : null
            );
        }
        String context = buildContext(contextDocs);

        if (mode == CitationMode.required) {
            return answerWithCitations(
                question, provider, context, sources, empty, retrievalMode, bundle, chunkingName
            );
        }

        var call = registry.plainClient(provider)
            .prompt()
            .system(SYSTEM_GROUNDED)
            .user("检索上下文：\n" + context + "\n\n用户问题：" + question)
            .call();
        return new RagQueryResult(
            call.content(),
            sources,
            empty,
            TokenUsageExtractor.from(call.chatResponse()),
            retrievalMode.name(),
            bundle.expansion().name(),
            bundle.hypotheticalDocument(),
            chunkingName,
            CitationMode.none.name(),
            List.of(),
            null
        );
    }

    private RagQueryResult answerWithCitations(
        String question,
        String provider,
        String context,
        List<SourceView> sources,
        boolean empty,
        RetrievalMode retrievalMode,
        RetrievalBundle bundle,
        String chunkingName
    ) {
        if (structuredOutputInvoker == null) {
            throw new IllegalStateException("citationMode=required 需要 StructuredOutputInvoker");
        }
        String userPrompt = "检索上下文（每段以 [id=...] 开头）：\n" + contextWithIds(sources, context)
            + "\n\n用户问题：" + question;
        try {
            StructuredOutputInvoker.InvokeResult<CitationValidator.GroundedAnswer> invoke =
                structuredOutputInvoker.invoke(
                    registry.plainClient(provider),
                    SYSTEM_CITED,
                    userPrompt,
                    CitationValidator.GroundedAnswer.class
                );
            CitationValidator.GroundedAnswer grounded = invoke.value();
            List<CitationValidator.Citation> citations =
                grounded == null || grounded.citations() == null ? List.of() : grounded.citations();
            Set<String> allowed = CitationValidator.idsOf(sources);
            CitationValidator.Result validation = CitationValidator.validate(citations, allowed);
            if (!validation.valid()) {
                log.info("RAG citation 校验失败: {}", validation.detail());
                return new RagQueryResult(
                    CITATION_REFUSAL,
                    sources,
                    empty,
                    invoke.usage(),
                    retrievalMode.name(),
                    bundle.expansion().name(),
                    bundle.hypotheticalDocument(),
                    chunkingName,
                    CitationMode.required.name(),
                    toCitationViews(citations),
                    false
                );
            }
            String answer = grounded.answer() == null ? "" : grounded.answer();
            return new RagQueryResult(
                answer,
                sources,
                empty,
                invoke.usage(),
                retrievalMode.name(),
                bundle.expansion().name(),
                bundle.hypotheticalDocument(),
                chunkingName,
                CitationMode.required.name(),
                toCitationViews(citations),
                true
            );
        } catch (RuntimeException ex) {
            log.warn("RAG citation 结构化失败: {}", ex.getMessage());
            return new RagQueryResult(
                CITATION_REFUSAL,
                sources,
                empty,
                null,
                retrievalMode.name(),
                bundle.expansion().name(),
                bundle.hypotheticalDocument(),
                chunkingName,
                CitationMode.required.name(),
                List.of(),
                false
            );
        }
    }

    private static List<CitationView> toCitationViews(List<CitationValidator.Citation> citations) {
        List<CitationView> views = new ArrayList<>();
        for (CitationValidator.Citation citation : citations) {
            if (citation == null) {
                continue;
            }
            views.add(new CitationView(
                citation.sourceId(),
                citation.quote() == null ? "" : citation.quote()
            ));
        }
        return List.copyOf(views);
    }

    /**
     * 在 user prompt 里强调 id；上下文正文仍用 buildContext 结果，并在前附加 id 列表。
     */
    private static String contextWithIds(List<SourceView> sources, String context) {
        StringBuilder sb = new StringBuilder();
        for (SourceView source : sources) {
            sb.append("[id=").append(source.id()).append("] source=")
                .append(source.source() == null ? "" : source.source()).append('\n');
        }
        sb.append('\n').append(context);
        return sb.toString();
    }

    static CitationMode parseCitationMode(String value) {
        if (value != null && value.trim().equalsIgnoreCase("required")) {
            return CitationMode.required;
        }
        return CitationMode.none;
    }

    private ChunkingView toChunkingView(ChunkingStrategy strategy, RetrievalBundle bundle) {
        // 分块对照需看完整 chunk，避免 160 字截断掩盖 token vs semantic 正文差异
        return new ChunkingView(
            strategy.name(),
            corpusFor(strategy),
            toSources(bundle.hits(), 0),
            isRetrievalEmpty(bundle.hits())
        );
    }

    static ChunkingStrategy parseChunkingStrategy(String value) {
        if (value == null || value.isBlank()) {
            return ChunkingStrategy.token;
        }
        String normalized = value.trim().toLowerCase().replace('-', '_');
        if ("semantic".equals(normalized)) {
            return ChunkingStrategy.semantic;
        }
        if ("parent_child".equals(normalized) || "parentchild".equals(normalized)) {
            return ChunkingStrategy.parent_child;
        }
        return ChunkingStrategy.token;
    }

    private String semanticCorpus() {
        return ragSettings.chunking().semanticCorpus();
    }

    private String parentCorpus() {
        return ragSettings.chunking().parentCorpus();
    }

    /** 供 Controller 解析 semantic corpus 名。 */
    public String semanticCorpusPublic() {
        return semanticCorpus();
    }

    /** 供 Controller 解析 parent corpus 名。 */
    public String parentCorpusPublic() {
        return parentCorpus();
    }

    /** 三套语料隔离：token / semantic / parent_child 互不覆盖。 */
    private String corpusFor(ChunkingStrategy strategy) {
        if (strategy == ChunkingStrategy.semantic) {
            return semanticCorpus();
        }
        if (strategy == ChunkingStrategy.parent_child) {
            return parentCorpus();
        }
        return CORPUS_DEMO;
    }

    /**
     * 子块命中后展开为去重父块，用于生成上下文；sources 仍展示子块。
     * <p>
     * {@code expand-parent=false} 时原样返回 hits，生成也只用子块正文。
     */
    List<Document> expandParents(List<Document> hits) {
        // 关闭展开：便于对照「只看子块上下文」的效果
        if (!ragSettings.chunking().expandParent() || hits == null || hits.isEmpty()) {
            return hits == null ? List.of() : hits;
        }
        // LinkedHashMap：按首次命中顺序保留父块，同 parentId 只留一份
        Map<String, Document> parents = new LinkedHashMap<>();
        List<Document> passthrough = new ArrayList<>();
        for (Document hit : hits) {
            Map<String, Object> meta = hit.getMetadata() == null ? Map.of() : hit.getMetadata();
            Object role = meta.get("chunkRole");
            Object parentText = meta.get("parentText");
            if ("child".equals(String.valueOf(role)) && parentText != null) {
                // 缺 parentId 时回退 hit.id，仍能去重展开
                String parentId = meta.get("parentId") == null
                    ? hit.getId()
                    : String.valueOf(meta.get("parentId"));
                if (!parents.containsKey(parentId)) {
                    Map<String, Object> parentMeta = new LinkedHashMap<>(meta);
                    // 标记为 parent，避免下游再次当子块处理
                    parentMeta.put("chunkRole", "parent");
                    parents.put(parentId, Document.builder()
                        .id(parentId)
                        .text(String.valueOf(parentText))
                        .metadata(parentMeta)
                        .build());
                }
            } else {
                // 非父子命中（或其他策略混入）原样保留，避免丢上下文
                passthrough.add(hit);
            }
        }
        List<Document> out = new ArrayList<>(parents.values());
        out.addAll(passthrough);
        return out;
    }

    /** 父全文截断供 UI 预览；LLM 上下文仍用完整 parentText。 */
    private static String parentExcerpt(Object parentText) {
        if (parentText == null) {
            return null;
        }
        String text = String.valueOf(parentText);
        if (text.isBlank()) {
            return null;
        }
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
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

    private List<Document> fuseWithKeyword(List<Document> primary, String keywordQuery, int k, String corpus) {
        int keywordK = ragSettings.hybrid().keywordTopK();
        List<Document> keywordHits = keywordRetriever.search(keywordQuery, keywordK, corpus);
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

    /**
     * 向量检索。
     * @param question  查询问题
     * @param k         返回结果数量
     * @param corpus    索引库
     * @return 检索结果列表
     */
    private List<Document> vectorRetrieve(String question, int k, String corpus) {
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(k)
                .filterExpression(META_CORPUS + " == '" + corpus + "'")
                .build()
        );
    }

    /**
     * 混合检索：向量 + 关键词 + RRF。
     */
    private List<Document> hybridRetrieve(String question, int k, String corpus) {
        List<Document> vectorHits = vectorRetrieve(question, k, corpus);
        int keywordK = ragSettings.hybrid().keywordTopK();
        List<Document> keywordHits = keywordRetriever.search(question, keywordK, corpus);

        List<String> vectorIds = vectorHits.stream().map(Document::getId).toList();
        List<String> keywordIds = keywordHits.stream().map(Document::getId).toList();
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

    public record IngestResult(
        int chunkCount,
        List<String> sources,
        String strategy,
        Map<String, Integer> corpora
    ) {
        /** 二期兼容：仅 chunkCount + sources。 */
        public IngestResult(int chunkCount, List<String> sources) {
            this(chunkCount, sources, ChunkingStrategy.token.name(), Map.of(CORPUS_DEMO, chunkCount));
        }
    }

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
     * @param chunkingStrategy       token / semantic
     * @param citationMode           none / required
     * @param citations              结构化引用；none 时为空列表
     * @param citationValid          required 时校验结果；none 时为 {@code null}
     */
    public record RagQueryResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        TokenUsage usage,
        String retrievalMode,
        String queryExpansion,
        String hypotheticalDocument,
        String chunkingStrategy,
        String citationMode,
        List<CitationView> citations,
        Boolean citationValid
    ) {
        /** 二期兼容：无 retrievalMode 字段时视为 vector。 */
        public RagQueryResult(String answer, List<SourceView> sources, boolean retrievalEmpty, TokenUsage usage) {
            this(answer, sources, retrievalEmpty, usage, RetrievalMode.vector.name(), QueryExpansion.none.name(), null, ChunkingStrategy.token.name(), CitationMode.none.name(), List.of(), null);
        }

        /** 第四期兼容：无 queryExpansion 字段。 */
        public RagQueryResult(
            String answer,
            List<SourceView> sources,
            boolean retrievalEmpty,
            TokenUsage usage,
            String retrievalMode
        ) {
            this(answer, sources, retrievalEmpty, usage, retrievalMode, QueryExpansion.none.name(), null, ChunkingStrategy.token.name(), CitationMode.none.name(), List.of(), null);
        }

        /** 第六期兼容：无 chunkingStrategy。 */
        public RagQueryResult(
            String answer,
            List<SourceView> sources,
            boolean retrievalEmpty,
            TokenUsage usage,
            String retrievalMode,
            String queryExpansion,
            String hypotheticalDocument
        ) {
            this(answer, sources, retrievalEmpty, usage, retrievalMode, queryExpansion, hypotheticalDocument, ChunkingStrategy.token.name(), CitationMode.none.name(), List.of(), null);
        }

        /** 第十期前兼容：无 citation 字段。 */
        public RagQueryResult(
            String answer,
            List<SourceView> sources,
            boolean retrievalEmpty,
            TokenUsage usage,
            String retrievalMode,
            String queryExpansion,
            String hypotheticalDocument,
            String chunkingStrategy
        ) {
            this(answer, sources, retrievalEmpty, usage, retrievalMode, queryExpansion, hypotheticalDocument, chunkingStrategy, CitationMode.none.name(), List.of(), null);
        }
    }

    /**
     * 对外暴露的 citation 视图。
     *
     * @param sourceId 检索文档 id
     * @param quote    摘录
     */
    public record CitationView(String sourceId, String quote) {}

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

    /** 分块策略对照单路（7a：sources 为完整 chunk 正文，便于对照切分差异）。 */
    public record ChunkingView(
        String chunkingStrategy,
        String corpus,
        List<SourceView> sources,
        boolean retrievalEmpty
    ) {}

    public record ChunkingCompareResult(ChunkingView token, ChunkingView semantic, ChunkingView parentChild) {
        /** 7a 兼容：仅两路。 */
        public ChunkingCompareResult(ChunkingView token, ChunkingView semantic) {
            this(token, semantic, null);
        }
    }

    public record SourceView(
        String id,
        String source,
        String excerpt,
        Map<String, Object> metadata,
        Integer vectorRank,
        Integer keywordRank,
        Double rrfScore,
        String chunkRole,
        String parentExcerpt
    ) {
        /** 二期兼容：无 rank 字段。 */
        public SourceView(String id, String source, String excerpt, Map<String, Object> metadata) {
            this(id, source, excerpt, metadata, null, null, null, null, null);
        }

        /** 第四～六期兼容：无 chunkRole。 */
        public SourceView(
            String id,
            String source,
            String excerpt,
            Map<String, Object> metadata,
            Integer vectorRank,
            Integer keywordRank,
            Double rrfScore
        ) {
            this(id, source, excerpt, metadata, vectorRank, keywordRank, rrfScore, null, null);
        }
    }
}
