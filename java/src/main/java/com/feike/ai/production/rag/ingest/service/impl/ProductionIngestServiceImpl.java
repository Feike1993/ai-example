package com.feike.ai.production.rag.ingest.service.impl;

import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;

import com.feike.ai.production.rag.ingest.service.ProductionIngestService;

import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.core.rag.SemanticMarkdownSplitter;
import com.feike.ai.production.config.ProductionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入库层：文档 → 分块 → 向量库。
 * <p>
 * 与教学样例最大的区别是「只有一种分块策略」。样例要同时维护 token / semantic / parent_child
 * 三套语料来做对比，生产链路固定用语义分块——章节边界比固定 token 窗口更少切断语义，
 * 而多策略并存的运维成本（三份索引、三次 embedding 费用、策略与查询必须对齐）在生产里不划算。
 * <p>
 * 写入前按 corpus 删旧，保证 ingest 幂等；corpus 与样例分开，教学页重建索引不会波及生产。
 */
public class ProductionIngestServiceImpl implements ProductionIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProductionIngestServiceImpl.class);

    /** 元数据键：语料隔离。 */
    public static final String META_CORPUS = "corpus";

    /** 元数据键：租户；捆绑语料写 {@code public}。 */
    public static final String META_TENANT_ID = "tenant_id";

    private final VectorStore vectorStore;
    private final RagKeywordRetriever keywordRetriever;
    private final ProductionProperties properties;
    private final SemanticMarkdownSplitter splitter;

    /**
     * @param vectorStore      pgvector
     * @param keywordRetriever 全文检索路；为 {@code null} 时跳过索引创建，检索退化为纯向量
     * @param properties       corpus 与 chunkSize
     */
    public ProductionIngestServiceImpl(
        VectorStore vectorStore,
        RagKeywordRetriever keywordRetriever,
        ProductionProperties properties
    ) {
        this.vectorStore = vectorStore;
        this.keywordRetriever = keywordRetriever;
        this.properties = properties;
        this.splitter = new SemanticMarkdownSplitter(properties.chunkSize());
    }

    /**
     * 幂等重建生产语料索引（捆绑文档标为 public，所有租户可读）。
     *
     * @return 入库结果
     */
    public IngestResult ingest() {
        return ingest(properties.agent().publicTenantId());
    }

    /**
     * 幂等重建生产语料索引。
     *
     * @param tenantId 写入 chunk 的租户标记
     * @return 入库结果
     */
    public IngestResult ingest(String tenantId) {
        String corpus = properties.corpus();
        String tenant = com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService.sanitizeTenant(tenantId);
        deleteCorpus(corpus);

        List<Document> sourceDocs = loadDocs(corpus);
        List<Document> chunks = splitter.apply(sourceDocs);
        for (Document chunk : chunks) {
            chunk.getMetadata().put(META_CORPUS, corpus);
            chunk.getMetadata().put(META_TENANT_ID, tenant);
        }
        vectorStore.add(chunks);
        if (keywordRetriever != null) {
            keywordRetriever.ensureFullTextIndex();
        }

        List<String> sources = sourceDocs.stream()
            .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("source", "unknown")))
            .distinct()
            .sorted()
            .toList();
        log.info("生产语料 ingest 完成: corpus={}, chunks={}, sources={}", corpus, chunks.size(), sources);
        return new IngestResult(corpus, chunks.size(), sources);
    }

    private void deleteCorpus(String corpus) {
        try {
            vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(META_CORPUS),
                new Filter.Value(corpus)
            ));
        } catch (RuntimeException ex) {
            // 首次 ingest 时表里没有该 corpus，删除失败属正常
            log.debug("清理旧生产语料时忽略: {}", ex.toString());
        }
    }

    private List<Document> loadDocs(String corpus) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:rag-docs/*.md");
            if (resources.length == 0) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "未找到 classpath:rag-docs/*.md");
            }
            List<Document> docs = new ArrayList<>(resources.length);
            for (Resource resource : resources) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("source", resource.getFilename() == null ? "unknown.md" : resource.getFilename());
                metadata.put(META_CORPUS, corpus);
                docs.add(Document.builder()
                    .text(resource.getContentAsString(StandardCharsets.UTF_8))
                    .metadata(metadata)
                    .build());
            }
            return docs;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取语料失败: " + ex.getMessage());
        }
    }

}
