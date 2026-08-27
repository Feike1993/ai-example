package com.feike.ai.core;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 从 Spring AI {@link ChatResponse} 提取 token 用量；失败或未上报时返回 {@code null}，不抛错。
 */
public final class TokenUsageExtractor {

    private TokenUsageExtractor() {}

    /**
     * @param response Spring AI 聊天响应，可为 {@code null}
     * @return 用量 DTO；无有效数据时为 {@code null}
     */
    public static TokenUsage from(ChatResponse response) {
        if (response == null) {
            return null;
        }
        try {
            Usage usage = response.getMetadata().getUsage();
            if (usage == null || usage instanceof EmptyUsage) {
                return null;
            }
            Integer prompt = usage.getPromptTokens();
            Integer completion = usage.getCompletionTokens();
            Integer total = usage.getTotalTokens();
            if (isZeroOrNull(prompt) && isZeroOrNull(completion) && isZeroOrNull(total)) {
                return null;
            }
            return new TokenUsage(prompt, completion, total);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isZeroOrNull(Integer value) {
        return value == null || value == 0;
    }
}
