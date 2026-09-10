package com.feike.ai.production.rag.retrieve.service.impl;

import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;

import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.core.rag.RrfFusion;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.rag.model.ProductionQueryExpansionEnum;
import com.feike.ai.production.rag.model.ProductionSource;
import com.feike.ai.production.rag.retrieve.manager.ProductionQueryExpander;
import com.feike.ai.production.rag.retrieve.model.ProductionRetrieveQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索层：向量路 + 可选全文路，用 RRF 融合。
 * <p>
 * 查询扩展（rewrite / HyDE）默认关闭。样例里那些扩展每次都要额外打一趟 LLM，
 * 在生产链路上意味着首字延迟翻倍、成本翻倍，而收益高度依赖语料；
 * 因此做成可灰度开关，而不是检索层默认行为。假想文档只作检索 query，不进 sources。
 * <p>
 * 全文路缺失（Redis 之外的降级场景：RAG 关闭时 {@link RagKeywordRetriever} 不存在）时
 * 自动退回纯向量，而不是报错——少一路召回仍能回答，直接失败不能。
 */
public class ProductionRetrievalServiceImpl implements ProductionRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ProductionRetrievalServiceImpl.class);

    private static final String META_CORPUS = "corpus";
    public static final String META_TENANT_ID = "tenant_id";
    private static final int EXCERPT_LIMIT = 240;

    private final VectorStore vectorStore;
    private final RagKeywordRetriever keywordRetriever;
    private final ProductionQueryExpander expander;
    private final ProductionProperties properties;

    /**
     * @param vectorStore      pgvector
     * @param keywordRetriever 全文路；可为 {@code null}
     * @param expander         查询扩展；可为 {@code null}（单测或强制 none）
     * @param properties       topK / RRF / corpus / 扩展默认
     */
    public ProductionRetrievalServiceImpl(
        VectorStore vectorStore,
        RagKeywordRetriever keywordRetriever,
        ProductionQueryExpander expander,
        ProductionProperties properties
    ) {
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;
        this.expander = expander;
        this.properties = properties;
    }

    /**
     * 检索并判定是否为空检索。
     *
     * @param question 用户问题
     * @param topK     覆盖默认 topK；{@code null} 用配置值
     * @return 检索结果
     */
    public RetrievalResult retrieve(String question, Integer topK) {
        return retrieve(question, topK, properties.agent().publicTenantId());
    }

    /**
     * 按租户过滤检索。捆绑语料 {@code public} 对所有租户可见。
     *
     * @param question 用户问题
     * @param topK     覆盖默认 topK
     * @param tenantId JWT 租户
     * @return 检索结果
     */
    public RetrievalResult retrieve(String question, Integer topK, String tenantId) {
        return retrieve(ProductionRetrieveQuery.of(question, topK, tenantId));
    }

    /**
     * 按查询对象检索。
     *
     * @param query 检索条件
     * @return 检索结果
     */
    public RetrievalResult retrieve(ProductionRetrieveQuery query) {
        String question = query == null ? null : query.question();
        Integer topK = query == null ? null : query.topK();
        String tenantId = query == null ? null : query.tenantId();
        int k = topK == null || topK < 1 ? properties.topK() : topK;
        String corpus = properties.corpus();
        String tenant = sanitizeTenant(tenantId);
        boolean hybrid = properties.hybridEnabled() && keywordRetriever != null;
        ProductionQueryExpansionEnum expansion = resolveExpansion(query);

        String rewrittenQuery = null;
        List<Document> hits;
        if (expansion == ProductionQueryExpansionEnum.REWRITE && expander != null) {
            String rewritten = expander.rewrite(question, query.provider());
            if (rewritten != null && !rewritten.equals(question)) {
                rewrittenQuery = rewritten;
                hits = search(rewritten, k, corpus, tenant, hybrid);
            } else {
                expansion = ProductionQueryExpansionEnum.NONE;
                hits = search(question, k, corpus, tenant, hybrid);
            }
        } else if (expansion == ProductionQueryExpansionEnum.HYDE && expander != null) {
            String hypo = expander.hypotheticalDocument(question, query.provider());
            if (hypo == null || hypo.equals(question)) {
                expansion = ProductionQueryExpansionEnum.NONE;
                hits = search(question, k, corpus, tenant, hybrid);
            } else {
                hits = hydeSearch(question, hypo, k, corpus, tenant, hybrid);
            }
        } else {
            expansion = ProductionQueryExpansionEnum.NONE;
            hits = search(question, k, corpus, tenant, hybrid);
        }

        boolean empty = hits.size() < properties.minSources();
        log.debug(
            "生产检索: hybrid={}, expansion={}, corpus={}, tenant={}, k={}, hits={}, empty={}",
            hybrid, expansion.getCode(), corpus, tenant, k, hits.size(), empty
        );
        return new RetrievalResult(
            hits,
            toSources(hits),
            empty,
            hybrid ? "hybrid" : "vector",
            expansion.getCode(),
            rewrittenQuery
        );
    }

    private ProductionQueryExpansionEnum resolveExpansion(ProductionRetrieveQuery query) {
        String requested = query == null ? null : query.queryExpansion();
        if (requested == null || requested.isBlank()) {
            return ProductionQueryExpansionEnum.from(properties.queryExpansion().defaultMode());
        }
        return ProductionQueryExpansionEnum.from(requested);
    }

    private List<Document> search(String question, int k, String corpus, String tenant, boolean hybrid) {
        if (hybrid) {
            return hybridRetrieve(question, k, corpus, tenant);
        }
        return vectorRetrieve(question, k, corpus, tenant);
    }

    /**
     * HyDE：向量路用假想文档；可选与原问题向量路融合；hybrid 时关键词路仍用原问题。
     */
    private List<Document> hydeSearch(
        String question,
        String hypo,
        int k,
        String corpus,
        String tenant,
        boolean hybrid
    ) {
        List<Document> hydeHits = vectorRetrieve(hypo, k, corpus, tenant);
        List<Document> hits = hydeHits;
        if (properties.queryExpansion().fuseHydeWithOriginal()) {
            hits = fuseDocumentLists(hydeHits, vectorRetrieve(question, k, corpus, tenant), k);
        }
        if (hybrid) {
            hits = fuseDocumentLists(
                hits,
                keywordRetriever.search(question, properties.keywordTopK(), corpus, tenant),
                k
            );
        }
        return hits;
    }

    private List<Document> vectorRetrieve(String question, int k, String corpus, String tenantId) {
        String publicId = sanitizeTenant(properties.agent().publicTenantId());
        String filter = META_CORPUS + " == '" + corpus + "' && ("
            + META_TENANT_ID + " == '" + publicId + "' || "
            + META_TENANT_ID + " == '" + tenantId + "')";
        List<Document> hits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(k)
                .filterExpression(filter)
                .build()
        );
        return hits == null ? List.of() : hits;
    }

    private List<Document> hybridRetrieve(String question, int k, String corpus, String tenantId) {
        List<Document> vectorHits = vectorRetrieve(question, k, corpus, tenantId);
        List<Document> keywordHits = keywordRetriever.search(question, properties.keywordTopK(), corpus, tenantId);
        return fuseDocumentLists(vectorHits, keywordHits, k);
    }

    private List<Document> fuseDocumentLists(List<Document> first, List<Document> second, int k) {
        List<Document> left = first == null ? List.of() : first;
        List<Document> right = second == null ? List.of() : second;
        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            left.stream().map(Document::getId).toList(),
            right.stream().map(Document::getId).toList(),
            properties.rrfK(),
            k
        );
        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document doc : left) {
            byId.put(doc.getId(), doc);
        }
        for (Document doc : right) {
            byId.putIfAbsent(doc.getId(), doc);
        }
        List<Document> merged = new ArrayList<>(fused.size());
        for (RrfFusion.RankedId ranked : fused) {
            Document doc = byId.get(ranked.id());
            if (doc != null) {
                merged.add(doc);
            }
        }
        return merged;
    }

    private static List<ProductionSource> toSources(List<Document> hits) {
        List<ProductionSource> views = new ArrayList<>(hits.size());
        for (Document doc : hits) {
            views.add(ProductionSource.of(doc.getId(), doc.getText(), doc.getMetadata(), EXCERPT_LIMIT));
        }
        return List.copyOf(views);
    }

    /**
     * 租户 id 只允许字母数字与 {@code _-}，避免拼进 filter 表达式时被注入。
     *
     * @param tenantId 原始租户
     * @return 安全值
     */
    public static String sanitizeTenant(String tenantId) {
        return ProductionRetrievalService.sanitizeTenant(tenantId);
    }
}
