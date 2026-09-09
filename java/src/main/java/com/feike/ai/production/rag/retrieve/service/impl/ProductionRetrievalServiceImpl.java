package com.feike.ai.production.rag.retrieve.service.impl;

import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;

import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.core.rag.RrfFusion;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.rag.model.ProductionSource;
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
 * 这一层刻意不做查询扩展（rewrite / HyDE）。样例里那些扩展每次都要额外打一趟 LLM，
 * 在生产链路上意味着首字延迟翻倍、成本翻倍，而收益高度依赖语料；
 * 真要上，应该是可灰度的独立策略，而不是检索层默认行为。
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
    private final ProductionProperties properties;

    /**
     * @param vectorStore      pgvector
     * @param keywordRetriever 全文路；可为 {@code null}
     * @param properties       topK / RRF / corpus
     */
    public ProductionRetrievalServiceImpl(
        VectorStore vectorStore,
        RagKeywordRetriever keywordRetriever,
        ProductionProperties properties
    ) {
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;
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
        int k = topK == null || topK < 1 ? properties.topK() : topK;
        String corpus = properties.corpus();
        String tenant = sanitizeTenant(tenantId);
        boolean hybrid = properties.hybridEnabled() && keywordRetriever != null;

        List<Document> hits = hybrid
            ? hybridRetrieve(question, k, corpus, tenant)
            : vectorRetrieve(question, k, corpus, tenant);

        boolean empty = hits.size() < properties.minSources();
        log.debug(
            "生产检索: hybrid={}, corpus={}, tenant={}, k={}, hits={}, empty={}",
            hybrid, corpus, tenant, k, hits.size(), empty
        );
        return new RetrievalResult(hits, toSources(hits), empty, hybrid ? "hybrid" : "vector");
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

        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            vectorHits.stream().map(Document::getId).toList(),
            keywordHits.stream().map(Document::getId).toList(),
            properties.rrfK(),
            k
        );

        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document doc : vectorHits) {
            byId.put(doc.getId(), doc);
        }
        for (Document doc : keywordHits) {
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
