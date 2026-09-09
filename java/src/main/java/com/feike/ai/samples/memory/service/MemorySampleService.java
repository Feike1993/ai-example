package com.feike.ai.samples.memory.service;

import com.feike.ai.core.model.TokenUsageDTO;
import java.util.List;
import java.util.Map;

/** MemorySampleService 业务门面。 */
public interface MemorySampleService {

    String META_CORPUS = "corpus";
    String CORPUS_MEMORY = "long-term-memory";
    String META_USER_ID = "userId";
    String META_SESSION_ID = "sessionId";
    String META_KIND = "kind";
    String EMPTY_REFUSAL =
        "根据当前长期记忆的检索结果，没有找到相关内容，因此无法依据记忆回答。"
            + "请先 remember 相关事实，或换个问法。";
    record SourceView(String id, String excerpt, Map<String, Object> metadata) {}
    record DialogueMessage(String role, String content) {}
    record RememberResult(String id, String userId, String text, boolean duplicate, boolean updated) {}
    record RecallResult(String userId, List<SourceView> sources, boolean empty, String note) {}
    record MemoryChatResult(String answer, List<SourceView> sources, boolean retrievalEmpty, String userId, TokenUsageDTO usage) {}
    record ClearResult(String userId, boolean cleared) {}
    record ExtractResult(String userId, String sessionId, List<String> facts, List<RememberResult> remembered, int skippedDuplicates) {}
    record RecallCompareResult(String userId, int lowTopKSize, int highTopKSize, double similarityThreshold, RecallResult lowTopK, RecallResult highTopK, RecallResult withThreshold) {}
    record ChatCompareResult(MemoryChatResult withMemory, MemoryChatResult withoutMemory) {}
    RememberResult remember(String text, String userId, String sessionId);
    RecallResult recall(String query, String userId, Integer topK, Double similarityThreshold);
    RecallResult recall(String query, String userId, Integer topK);
    RecallCompareResult compareRecall(String query, String userId, Integer lowTopK, Integer highTopK, Double similarityThreshold);
    ChatCompareResult compareChat(String prompt, String userId, String provider, Integer topK, Boolean generateAnswers);
    MemoryChatResult chat(String prompt, String userId, String provider, Integer topK);
    ExtractResult extract(List<DialogueMessage> messages, String userId, String sessionId, String provider);
    ExtractResult extractFromSession(String sessionId, String userId, String provider);
    ClearResult clear(String userId);
}
