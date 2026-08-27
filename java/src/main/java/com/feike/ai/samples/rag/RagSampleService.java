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
import java.util.List;
import java.util.Map;

/**
 * RAG 样例：内置 Markdown → 分块 Embedding 写入 pgvector，再检索拼上下文生成。
 * <p>
 * 命中条数低于 {@code app.ai.rag.min-sources} 时标记 {@code retrievalEmpty}；
 * 若开启 {@code skip-llm-when-empty} 则直接固定拒答，避免模型编造。
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

    private final VectorStore vectorStore;
    private final LlmProviderRegistry registry;
    private final AiProperties.Rag ragSettings;
    private final TokenTextSplitter splitter;

    /**
     * @param vectorStore  pgvector
     * @param registry     Chat / Embedding
     * @param ragSettings  topK / chunkSize / 空检索策略
     */
    public RagSampleService(VectorStore vectorStore, LlmProviderRegistry registry, AiProperties.Rag ragSettings) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.ragSettings = ragSettings;
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
            // 空表或尚无过滤索引时允许继续
            log.debug("清理旧索引时忽略: {}", ex.toString());
        }

        List<Document> sourceDocs = loadClasspathDocs();
        // 分块
        List<Document> chunks = splitter.apply(sourceDocs);
        // 添加元数据
        for (Document chunk : chunks) {
            chunk.getMetadata().putIfAbsent(META_CORPUS, CORPUS_DEMO);
        }
        vectorStore.add(chunks);
        List<String> sources = sourceDocs.stream()
            .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "unknown")))
            .distinct()
            .sorted()
            .toList();
        log.info("RAG ingest 完成: chunks={}, sources={}", chunks.size(), sources);
        return new IngestResult(chunks.size(), sources);
    }

    /**
     * 检索 topK + 拼上下文 + 同步生成，并回传 sources / retrievalEmpty。
     *
     * @param question 用户问题
     * @param provider Chat Provider
     * @param topK     可选覆盖
     * @return 答案与来源摘要
     */
    public RagQueryResult query(String question, String provider, Integer topK) {
        List<Document> hits = retrieve(question, topK);
        boolean empty = isRetrievalEmpty(hits);
        if (empty && ragSettings.skipLlmWhenEmpty()) {
            log.info("RAG 空检索短路拒答: question={}, hits={}", question, hits.size());
            return new RagQueryResult(EMPTY_REFUSAL, toSources(hits), true, null);
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
            TokenUsageExtractor.from(call.chatResponse())
        );
    }

    /**
     * 流式 RAG 回答（先检索再 SSE）。
     *
     * @param question 用户问题
     * @param provider Chat Provider
     * @param topK     可选覆盖
     * @return 增量文本
     */
    public Flux<String> queryStream(String question, String provider, Integer topK) {
        return streamAnswer(question, provider, retrieve(question, topK));
    }

    /**
     * 在已检索结果上做流式生成，避免 Controller 重复检索。
     * <p>
     * 空检索且 {@code skipLlmWhenEmpty} 时直接推送固定拒答，不调 LLM。
     *
     * @param question 用户问题
     * @param provider Chat Provider
     * @param hits     检索命中
     * @return 增量文本
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
     * 仅检索，供流式接口附带 sources。
     *
     * @param question 用户问题
     * @param topK     可选覆盖
     * @return 命中文档
     */
    public List<Document> retrieve(String question, Integer topK) {
        int k = topK != null && topK > 0 ? topK : ragSettings.topK();
        // 相似度检索
        return vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(question)
                .topK(k)
                .filterExpression(META_CORPUS + " == '" + CORPUS_DEMO + "'")
                .build()
        );
    }

    /**
     * 命中条数是否低于 {@code min-sources}（含完全无命中）。
     *
     * @param hits 检索结果
     * @return true 表示空 / 低召回
     */
    public boolean isRetrievalEmpty(List<Document> hits) {
        int size = hits == null ? 0 : hits.size();
        return size < ragSettings.minSources();
    }

    /**
     * 把命中文档转成前端可展示的摘要。
     *
     * @param hits 检索结果
     * @return sources
     */
    public List<SourceView> toSources(List<Document> hits) {
        List<SourceView> views = new ArrayList<>();
        if (hits == null) {
            return views;
        }
        for (Document doc : hits) {
            String text = doc.getText() == null ? "" : doc.getText();
            String excerpt = text.length() > 160 ? text.substring(0, 160) + "…" : text;
            views.add(new SourceView(
                doc.getId(),
                String.valueOf(doc.getMetadata().getOrDefault("source", "")),
                excerpt,
                doc.getMetadata()
            ));
        }
        return views;
    }

    /**
     * 把命中文档拼成上下文。
     *
     * @param hits 检索结果
     * @return 拼接的文本
     */
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

    /**
     * 从 classpath 加载 Markdown 样例文档。
     * @return 文档列表
     */
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

    /**
     * @param chunkCount 写入 chunk 数
     * @param sources    源文件名
     */
    public record IngestResult(int chunkCount, List<String> sources) {}

    /**
     * @param answer          模型回答或空检索固定拒答
     * @param sources         检索来源摘要（空检索时通常为空列表）
     * @param retrievalEmpty  命中是否低于 min-sources
     * @param usage           token 用量；短路拒答或网关未返回时为 {@code null}
     */
    public record RagQueryResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        TokenUsage usage
    ) {}

    /**
     * @param id       chunk id
     * @param source   源文件
     * @param excerpt  文本摘要
     * @param metadata 完整元数据
     */
    public record SourceView(String id, String source, String excerpt, Map<String, Object> metadata) {}
}
