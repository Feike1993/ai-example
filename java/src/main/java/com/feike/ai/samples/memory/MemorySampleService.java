package com.feike.ai.samples.memory;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.context.ChatSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 长期记忆样例：写入 / 召回 / 自动抽事实写入 pgvector 独立 corpus，与 RAG 演示语料隔离。
 * <p>
 * 抽取走显式 {@code extract}，不在上下文 chat 里静默写入，避免副作用难排查。
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

    private static final String SYSTEM_EXTRACT = """
        你从对话中抽取适合写入长期记忆的短事实句。
        只输出 JSON 字符串数组，例如 ["用户叫小明","用户住在杭州"]；不要解释、不要 Markdown 围栏。
        每条一句、陈述句、简体中文；不要编造对话未出现的信息；最多若干条由用户消息说明。
        """;

    private final VectorStore vectorStore;
    private final LlmProviderRegistry registry;
    private final AiProperties.Memory memorySettings;
    private final JsonMapper jsonMapper;
    private final ObjectProvider<ChatSessionStore> sessionStore;

    public MemorySampleService(
        VectorStore vectorStore,
        LlmProviderRegistry registry,
        AiProperties properties,
        JsonMapper jsonMapper,
        ObjectProvider<ChatSessionStore> sessionStore
    ) {
        this.vectorStore = vectorStore;
        this.registry = registry;
        this.memorySettings = properties.memory();
        this.jsonMapper = jsonMapper;
        this.sessionStore = sessionStore;
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
     *
     * @param similarityThreshold 可选；有 Document score 时按阈值过滤，无 score 则仅 topK
     */
    public RecallResult recall(String query, String userId, Integer topK, Double similarityThreshold) {
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
        if (hits == null) {
            hits = List.of();
        }
        Double threshold = similarityThreshold;
        boolean scoreAvailable = hits.stream().anyMatch(doc -> doc.getScore() != null);
        if (threshold != null && scoreAvailable) {
            double t = threshold;
            hits = hits.stream()
                .filter(doc -> doc.getScore() != null && doc.getScore() >= t)
                .toList();
        }
        String note = null;
        if (threshold != null && !scoreAvailable && !hits.isEmpty()) {
            note = "向量库未返回 score，similarityThreshold 未生效，仅 topK 生效";
        }
        return new RecallResult(uid, toSources(hits), hits.isEmpty(), note);
    }

    /** 兼容旧调用：无阈值。 */
    public RecallResult recall(String query, String userId, Integer topK) {
        return recall(query, userId, topK, null);
    }

    /**
     * 三路召回对照：小 topK / 大 topK / 大 topK+阈值（默认不生成答案）。
     */
    public RecallCompareResult compareRecall(
        String query,
        String userId,
        Integer lowTopK,
        Integer highTopK,
        Double similarityThreshold
    ) {
        int low = lowTopK != null && lowTopK > 0 ? lowTopK : 1;
        int high = highTopK != null && highTopK > 0 ? highTopK : Math.max(memorySettings.topK() * 2, 8);
        double threshold = similarityThreshold != null
            ? similarityThreshold
            : memorySettings.similarityThreshold();
        RecallResult lowResult = recall(query, userId, low, null);
        RecallResult highResult = recall(query, userId, high, null);
        RecallResult thresholdResult = recall(query, userId, high, threshold);
        return new RecallCompareResult(
            lowResult.userId(),
            low,
            high,
            threshold,
            lowResult,
            highResult,
            thresholdResult
        );
    }

    /**
     * 有记忆 vs 无记忆纯 Chat 对照。
     *
     * @param generateAnswers 默认 true；false 时 without 仅占位、with 仍走 recall 不生成
     */
    public ChatCompareResult compareChat(
        String prompt,
        String userId,
        String provider,
        Integer topK,
        Boolean generateAnswers
    ) {
        boolean generate = generateAnswers == null || generateAnswers;
        MemoryChatResult withMemory;
        if (generate) {
            withMemory = chat(prompt, userId, provider, topK);
        } else {
            RecallResult recalled = recall(prompt, userId, topK);
            withMemory = new MemoryChatResult(
                recalled.empty() ? EMPTY_REFUSAL : "（generateAnswers=false，跳过生成）",
                recalled.sources(),
                recalled.empty(),
                recalled.userId(),
                null
            );
        }

        MemoryChatResult withoutMemory;
        if (!generate) {
            withoutMemory = new MemoryChatResult(
                "（generateAnswers=false，跳过生成）",
                List.of(),
                true,
                resolveUserId(userId),
                null
            );
        } else {
            var call = registry.plainClient(provider)
                .prompt()
                .system("你是助手。用简体中文回答；没有依据时可以说不知道。")
                .user(prompt)
                .call();
            withoutMemory = new MemoryChatResult(
                call.content(),
                List.of(),
                true,
                resolveUserId(userId),
                TokenUsageExtractor.from(call.chatResponse())
            );
        }
        return new ChatCompareResult(withMemory, withoutMemory);
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
     * 从对话消息列表抽取短事实并 remember。
     *
     * @param messages  role/content 列表（user/assistant）
     * @param userId    用户
     * @param sessionId 可选写入 metadata
     * @param provider  Chat Provider
     */
    public ExtractResult extract(
        List<DialogueMessage> messages,
        String userId,
        String sessionId,
        String provider
    ) {
        String uid = resolveUserId(userId);
        List<DialogueMessage> dialogue = messages == null ? List.of() : messages.stream()
            .filter(m -> m != null && m.content() != null && !m.content().isBlank())
            .toList();
        if (dialogue.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        String transcript = formatTranscript(dialogue);
        int maxFacts = memorySettings.extractMaxFacts();
        List<String> facts;
        try {
            String raw = registry.plainClient(provider)
                .prompt()
                .system(SYSTEM_EXTRACT)
                .user("最多抽取 " + maxFacts + " 条事实。\n\n对话：\n" + transcript)
                .call()
                .content();
            facts = parseFactList(raw, maxFacts);
        } catch (Exception ex) {
            log.warn("自动抽记忆 Chat 失败，返回空: {}", ex.toString());
            facts = List.of();
        }

        List<RememberResult> remembered = new ArrayList<>();
        int skippedDuplicates = 0;
        for (String fact : facts) {
            RememberResult result = remember(fact, uid, sessionId);
            remembered.add(result);
            if (result.duplicate()) {
                skippedDuplicates++;
            }
        }
        return new ExtractResult(uid, sessionId, facts, remembered, skippedDuplicates);
    }

    /**
     * 从会话快照抽取；会话不存在或为空时抛 {@link IllegalArgumentException}。
     */
    public ExtractResult extractFromSession(String sessionId, String userId, String provider) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        ChatSessionStore store = sessionStore.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("会话存储不可用，请改传 messages");
        }
        List<Message> snapshot = store.snapshot(sessionId.trim());
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("会话不存在或为空: " + sessionId.trim());
        }
        List<DialogueMessage> messages = new ArrayList<>();
        for (Message message : snapshot) {
            if (!ChatSessionStore.isUserOrAssistant(message)) {
                continue;
            }
            messages.add(new DialogueMessage(
                ChatSessionStore.roleOf(message),
                ChatSessionStore.textOf(message)
            ));
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("会话无可抽取的 user/assistant 消息: " + sessionId.trim());
        }
        return extract(messages, userId, sessionId.trim(), provider);
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

    private static String formatTranscript(List<DialogueMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (DialogueMessage message : messages) {
            String role = message.role() == null || message.role().isBlank() ? "user" : message.role();
            sb.append(role).append(": ").append(message.content().trim()).append("\n");
        }
        return sb.toString();
    }

    List<String> parseFactList(String raw, int maxFacts) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        try {
            List<String> parsed = jsonMapper.readValue(text, new TypeReference<List<String>>() {});
            if (parsed == null) {
                return List.of();
            }
            List<String> facts = new ArrayList<>();
            for (String item : parsed) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                facts.add(item.trim());
                if (facts.size() >= maxFacts) {
                    break;
                }
            }
            return facts;
        } catch (Exception ex) {
            log.warn("解析抽取 JSON 失败: {}", ex.toString());
            return List.of();
        }
    }

    /**
     * @param duplicate 文本完全相同，未改库
     * @param updated   相似合并：已删旧写新
     */
    public record RememberResult(String id, String userId, String text, boolean duplicate, boolean updated) {}

    public record RecallResult(String userId, List<SourceView> sources, boolean empty, String note) {
        public RecallResult(String userId, List<SourceView> sources, boolean empty) {
            this(userId, sources, empty, null);
        }
    }

    public record MemoryChatResult(
        String answer,
        List<SourceView> sources,
        boolean retrievalEmpty,
        String userId,
        TokenUsage usage
    ) {}

    public record ClearResult(String userId, boolean cleared) {}

    public record SourceView(String id, String excerpt, Map<String, Object> metadata) {}

    /** 抽取用的对话消息。 */
    public record DialogueMessage(String role, String content) {}

    /**
     * @param facts              模型抽出的事实（写入前）
     * @param remembered         每条 remember 结果
     * @param skippedDuplicates  其中 duplicate=true 的条数
     */
    public record ExtractResult(
        String userId,
        String sessionId,
        List<String> facts,
        List<RememberResult> remembered,
        int skippedDuplicates
    ) {}

    /**
     * 召回三路对照。
     *
     * @param lowTopKSize           窄召回所用 topK
     * @param highTopKSize          宽召回所用 topK
     * @param similarityThreshold   withThreshold 路使用的阈值
     * @param lowTopK               窄召回结果
     * @param highTopK              宽召回结果
     * @param withThreshold         宽召回 + 阈值过滤
     */
    public record RecallCompareResult(
        String userId,
        int lowTopKSize,
        int highTopKSize,
        double similarityThreshold,
        RecallResult lowTopK,
        RecallResult highTopK,
        RecallResult withThreshold
    ) {}

    /** 有记忆 / 无记忆 chat 对照。 */
    public record ChatCompareResult(MemoryChatResult withMemory, MemoryChatResult withoutMemory) {}
}
