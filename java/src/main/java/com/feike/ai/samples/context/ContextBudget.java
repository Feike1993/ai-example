package com.feike.ai.samples.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算：按「近似 token」与最大消息数裁剪或准备摘要输入。
 * <p>
 * 近似 token = 字符数 / 4（启发式，不接精确 tokenizer）。
 */
public final class ContextBudget {

    private ContextBudget() {}

    /**
     * 估算消息列表的近似 token 数。
     *
     * @param messages 消息
     * @return 近似值
     */
    public static int approxTokens(List<Message> messages) {
        int chars = 0;
        for (Message message : messages) {
            String text = message.getText();
            if (text != null) {
                chars += text.length();
            }
        }
        return Math.max(0, chars / 4);
    }

    /**
     * Trim：保留全部 system，再从尾部保留 user/assistant，直到不超过预算。
     *
     * @param history     历史（不含本轮 user）
     * @param maxMessages 最大消息条数（含 system）
     * @param tokenBudget 近似 token 上限
     * @return 裁剪结果
     */
    public static TrimResult trim(List<Message> history, int maxMessages, int tokenBudget) {
        List<Message> systems = new ArrayList<>();
        List<Message> turns = new ArrayList<>();
        for (Message message : history) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                systems.add(message);
            } else if (ChatSessionStore.isUserOrAssistant(message)) {
                turns.add(message);
            }
        }

        List<Message> keptTurns = new ArrayList<>(turns);
        int dropped = 0;
        while (!keptTurns.isEmpty()) {
            List<Message> candidate = new ArrayList<>(systems);
            candidate.addAll(keptTurns);
            if (candidate.size() <= maxMessages && approxTokens(candidate) <= tokenBudget) {
                return new TrimResult(candidate, dropped, approxTokens(candidate));
            }
            // 丢掉最旧的一对（若只剩一条则丢一条）
            keptTurns.removeFirst();
            dropped++;
            if (!keptTurns.isEmpty() && keptTurns.getFirst().getMessageType() == MessageType.ASSISTANT) {
                keptTurns.removeFirst();
                dropped++;
            }
        }
        List<Message> onlySystem = new ArrayList<>(systems);
        return new TrimResult(onlySystem, dropped + turns.size(), approxTokens(onlySystem));
    }

    /**
     * 为 summarize 策略拆出「可压缩的旧轮次」与「保留的最近轮次」。
     *
     * @param history       历史
     * @param keepRecent    最近保留的 user/assistant 条数
     * @param maxMessages   最终窗口消息上限
     * @param tokenBudget   预算（用于决定是否需要摘要）
     * @return 拆分结果；若不需要摘要则 {@code toSummarize} 为空
     */
    public static SummarizePlan planSummarize(
        List<Message> history,
        int keepRecent,
        int maxMessages,
        int tokenBudget
    ) {
        List<Message> systems = new ArrayList<>();
        List<Message> turns = new ArrayList<>();
        for (Message message : history) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                systems.add(message);
            } else if (ChatSessionStore.isUserOrAssistant(message)) {
                turns.add(message);
            }
        }
        List<Message> full = new ArrayList<>(systems);
        full.addAll(turns);
        if (full.size() <= maxMessages && approxTokens(full) <= tokenBudget) {
            return new SummarizePlan(List.of(), List.copyOf(turns), systems, false);
        }
        int keep = Math.max(2, keepRecent);
        if (turns.size() <= keep) {
            TrimResult trimmed = trim(history, maxMessages, tokenBudget);
            return new SummarizePlan(List.of(), extractTurns(trimmed.messages()), systems, false);
        }
        int split = turns.size() - keep;
        List<Message> oldTurns = new ArrayList<>(turns.subList(0, split));
        List<Message> recent = new ArrayList<>(turns.subList(split, turns.size()));
        return new SummarizePlan(oldTurns, recent, systems, true);
    }

    /**
     * 把摘要插入为一条 system 旁注，再拼最近轮次，必要时再 trim。
     *
     * @param systems     原有 system
     * @param summaryText 摘要正文
     * @param recentTurns 最近轮次
     * @param maxMessages 上限
     * @param tokenBudget 预算
     * @return 最终窗口
     */
    public static TrimResult assembleWithSummary(
        List<Message> systems,
        String summaryText,
        List<Message> recentTurns,
        int maxMessages,
        int tokenBudget
    ) {
        List<Message> assembled = new ArrayList<>(systems);
        assembled.add(new SystemMessage("【历史摘要】\n" + summaryText));
        assembled.addAll(recentTurns);
        return trim(assembled, maxMessages, tokenBudget);
    }

    private static List<Message> extractTurns(List<Message> messages) {
        List<Message> turns = new ArrayList<>();
        for (Message message : messages) {
            if (ChatSessionStore.isUserOrAssistant(message)) {
                turns.add(message);
            }
        }
        return turns;
    }

    /**
     * @param messages     送入模型的消息
     * @param droppedCount 丢掉的历史条数
     * @param approxTokens 近似 token
     */
    public record TrimResult(List<Message> messages, int droppedCount, int approxTokens) {}

    /**
     * @param toSummarize   待摘要的旧轮次
     * @param recentTurns   保留的最近轮次
     * @param systems       system 消息
     * @param needsSummary  是否需要调用摘要 LLM
     */
    public record SummarizePlan(
        List<Message> toSummarize,
        List<Message> recentTurns,
        List<Message> systems,
        boolean needsSummary
    ) {}
}
