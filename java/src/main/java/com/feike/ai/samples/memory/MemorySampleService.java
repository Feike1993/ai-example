package com.feike.ai.samples.memory;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 长期记忆样例：写入 / 召回 pgvector 独立 corpus，与 RAG 演示语料隔离。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemorySampleService {

    private static final Logger log = LoggerFactory.getLogger(MemorySampleService.class);

    /** 与 RAG {@code ai-example-demo} 隔离。 */
    public static final String META_CORPUS = "corpus";
    public static final String CORPUS_MEMORY = "long-term-memory";
    public static final String META_USER_ID = "userId";
    public static final String META_SESSION_ID = "sessionId";
    public static final String META_KIND = "kind";

    public static final String EMPTY_REFUSAL =
        "根据当前长期记忆的检索结果，没有找到相关内容，因此无法依据记忆回答。"
            + "请先 remember 相关事实，或换个问法。";

    private static final String SYSTEM_GROUNDED = """
        你是助手。只根据「长期记忆」回答用户问题；记忆不足或为空时明确说不知道，不要编造。
        事实内容必须来自长期记忆，不得添加记忆中没有的信息。
        允许修正明显笔误或错别字（例如「生活中杭州」写成「生活在杭州」），但不得改动实体、偏好等实质含义。
        回答用简体中文。
        """;

    private final VectorStore vectorStore;
    private final LlmProviderRegistry registry;
    private final AiProperties.Memory memorySettings;

    public MemorySampleService(
        VectorStore vectorStore,
        LlmProviderRegistry registry,
        AiProperties properties
    ) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.memorySettings = properties.memory();
    }

    /**
     * 写入一条事实记忆：精确相同则跳过；语义相似则删旧写新；否则新增。
     *
     * @param text      事实文本
     * @param userId    用户 id；空则用默认
     * @param sessionId 可选会话关联
     * @return 写入结果（含 duplicate / updated）
     */
    public RememberResult remember(String text, String userId, String sessionId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        String uid = resolveUserId(userId);
        String normalized = text.trim();
        Optional<Document> similar = findSimilarMemory(normalized, uid);

        if (similar.isPresent()) {
            Document existing = similar.get();
            String existingText = existing.getText() == null ? "" : existing.getText().trim();
            if (normalized.equals(existingText)) {
                log.info("长期记忆已存在，跳过重复写入: userId={}, id={}", uid, existing.getId());
                return new RememberResult(existing.getId(), uid, normalized, true, false);
            }
            String oldId = existing.getId();
            vectorStore.delete(List.of(oldId));
            Document replaced = buildFactDocument(normalized, uid, sessionId);
            vectorStore.add(List.of(replaced));
            log.info(
                "长期记忆相似合并更新: userId={}, oldId={}, newId={}, score={}",
                uid,
                oldId,
                replaced.getId(),
                existing.getScore()
            );
            return new RememberResult(replaced.getId(), uid, normalized, false, true);
        }

        Document doc = buildFactDocument(normalized, uid, sessionId);
        vectorStore.add(List.of(doc));
        log.info("长期记忆写入: userId={}, id={}", uid, doc.getId());
        return new RememberResult(doc.getId(), uid, normalized, false, false);
    }

    /**
     * 按相似度召回记忆。
     */
    public RecallResult recall(String query, String userId, Integer topK) {
        String uid = resolveUserId(userId);
        int k = topK != null && topK > 0 ? topK : memorySettings.topK();
        List<Document> hits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(k)
                .filterExpression(
                    META_CORPUS + " == '" + CORPUS_MEMORY + "' && "
                        + META_USER_ID + " == '" + uid + "'"
                )
                .build()
        );
        return new RecallResult(uid, toSources(hits), hits.isEmpty());
    }

    /**
     * 先召回再生成。
     */
    public MemoryChatResult chat(String prompt, String userId, String provider, Integer topK) {
        RecallResult recalled = recall(prompt, userId, topK);
        if (recalled.empty()) {
            return new MemoryChatResult(
                EMPTY_REFUSAL,
                recalled.sources(),
                true,
                recalled.userId(),
                null
            );
        }
        String context = buildContext(recalled.sources());
        var call = registry.plainClient(provider)
            .prompt()
            .system(SYSTEM_GROUNDED)
            .user("长期记忆：\n" + context + "\n\n用户问题：" + prompt)
            .call();
        return new MemoryChatResult(
            call.content(),
            recalled.sources(),
            false,
            recalled.userId(),
            TokenUsageExtractor.from(call.chatResponse())
        );
    }

    /**
     * 按 userId 删除该用户全部长期记忆。
     *
     * @return 是否尝试删除（向量库无计数时恒为 true）
     */
    public ClearResult clear(String userId) {
        String uid = resolveUserId(userId);
        try {
            vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key(META_CORPUS),
                    new Filter.Value(CORPUS_MEMORY)
                ),
                new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key(META_USER_ID),
                    new Filter.Value(uid)
                )
            ));
        } catch (Exception ex) {
            log.warn("清除长期记忆时忽略: {}", ex.toString());
        }
        return new ClearResult(uid, true);
    }

    private Document buildFactDocument(String text, String userId, String sessionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_CORPUS, CORPUS_MEMORY);
        metadata.put(META_USER_ID, userId);
        metadata.put(META_KIND, "fact");
        if (sessionId != null && !sessionId.isBlank()) {
            metadata.put(META_SESSION_ID, sessionId.trim());
        }
        return Document.builder()
            .text(text)
            .metadata(metadata)
            .build();
    }

    private String resolveUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return memorySettings.userIdDefault();
        }
        return userId.trim();
    }

    private static List<SourceView> toSources(List<Document> hits) {
        List<SourceView> views = new ArrayList<>();
        if (hits == null) {
            return views;
        }
        Set<String> seenTexts = new LinkedHashSet<>();
        for (Document doc : hits) {
            String text = doc.getText() == null ? "" : doc.getText().trim();
            if (text.isEmpty() || !seenTexts.add(text)) {
                continue;
            }
            String excerpt = text.length() > 200 ? text.substring(0, 200) + "…" : text;
            views.add(new SourceView(
                doc.getId(),
                excerpt,
                doc.getMetadata() == null ? Map.of() : doc.getMetadata()
            ));
        }
        return views;
    }

    /**
     * 查找可合并的近邻：精确文本优先；否则取 score≥阈值的最高分文档。
     */
    private Optional<Document> findSimilarMemory(String text, String userId) {
        List<Document> candidates = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(text)
                .topK(20)
                .similarityThreshold(memorySettings.similarityThreshold()) // 阈值
                .filterExpression(
                    META_CORPUS + " == '" + CORPUS_MEMORY + "' && "
                        + META_USER_ID + " == '" + userId + "'"
                )
                .build()
        );
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (Document doc : candidates) {
            String existing = doc.getText() == null ? "" : doc.getText().trim();
            if (text.equals(existing)) {
                return Optional.of(doc);
            }
        }
        return candidates.stream()
            .filter(doc -> doc.getScore() != null && doc.getScore() >= memorySettings.similarityThreshold())
            .max(Comparator.comparingDouble(Document::getScore));
    }

    private static String buildContext(List<SourceView> sources) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                .append(sources.get(i).excerpt())
                .append("\n");
        }
        return sb.toString();
    }

    /**
     * @param duplicate 文本完全相同，未改库
     * @param updated   相似合并：已删旧写新
     */
    public record RememberResult(String id, String userId, String text, boolean duplicate, boolean updated) {}

    public record RecallResult(String userId, List<SourceView> sources, boolean empty) {}

    public record MemoryChatResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        String userId,
        TokenUsage usage
    ) {}

    public record ClearResult(String userId, boolean cleared) {}

    public record SourceView(String id, String excerpt, Map<String, Object> metadata) {}
}
