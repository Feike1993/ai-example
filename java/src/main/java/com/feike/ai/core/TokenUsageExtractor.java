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

    /**
     * 累加两次用量；任一侧为 null 时返回另一侧；字段分别相加（null 当 0，结果全 0 则返回 null）。
     *
     * @param left  左侧用量
     * @param right 右侧用量
     * @return 合计；两边皆无效时为 {@code null}
     */
    public static TokenUsage sum(TokenUsage left, TokenUsage right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        int prompt = nz(left.prompt()) + nz(right.prompt());
        int completion = nz(left.completion()) + nz(right.completion());
        int total = nz(left.total()) + nz(right.total());
        if (prompt == 0 && completion == 0 && total == 0) {
            return null;
        }
        // 若网关只给了 prompt/completion 未给 total，用二者之和填 total
        Integer totalOut = total > 0 ? total : (prompt + completion > 0 ? prompt + completion : null);
        return new TokenUsage(
            prompt > 0 ? prompt : null,
            completion > 0 ? completion : null,
            totalOut
        );
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
