package com.feike.ai.samples.context;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文工程样例：多轮会话 + trim / summarize 预算策略。
 */
@Service
public class ContextSampleService {

    private static final String DEFAULT_SYSTEM = """
        你是助手。根据对话历史回答；若历史不足就如实说明。
        用简体中文回答。
        """;

    private final InMemoryChatSessionStore store;
    private final LlmProviderRegistry registry;
    private final AiProperties.ContextSettings contextSettings;

    /**
     * @param store      进程内会话
     * @param registry   LLM
     * @param properties 读取 context 预算
     */
    public ContextSampleService(
        InMemoryChatSessionStore store,
        LlmProviderRegistry registry,
        AiProperties properties
    ) {
        this.store = store;
        this.registry = registry;
        this.contextSettings = properties.context();
    }

    /**
     * 一轮带记忆的聊天。
     *
     * @param sessionId 可空，空则新建
     * @param prompt    本轮用户输入
     * @param provider  Chat Provider
     * @param strategy  trim / summarize
     * @return 回复与预算元数据
     */
    public ContextChatResult chat(String sessionId, String prompt, String provider, ContextStrategy strategy) {
        String id = store.resolveSessionId(sessionId);
        List<Message> history = store.snapshot(id);
        if (history.isEmpty()) {
            history = new ArrayList<>();
            history.add(InMemoryChatSessionStore.asSystem(DEFAULT_SYSTEM));
            store.replace(id, history);
            history = store.snapshot(id);
        }

        int maxMessages = contextSettings.maxMessages();
        int tokenBudget = contextSettings.tokenBudget();
        int keepRecent = contextSettings.keepRecentMessages();

        List<Message> window;
        int dropped = 0;
        String summary = null;
        int approx;

        if (strategy == ContextStrategy.SUMMARIZE) {
            // 计划 summarize
            ContextBudget.SummarizePlan plan = ContextBudget.planSummarize(
                history, keepRecent, maxMessages, tokenBudget
            );
            if (plan.needsSummary() && !plan.toSummarize().isEmpty()) {
                // 执行 summarize 压缩
                summary = summarize(plan.toSummarize(), provider);
                // 计划 assemble
                ContextBudget.TrimResult assembled = ContextBudget.assembleWithSummary(
                    plan.systems(), summary, plan.recentTurns(), maxMessages, tokenBudget
                );
                window = new ArrayList<>(assembled.messages());
                dropped = assembled.droppedCount();
                approx = assembled.approxTokens();
            } else {
                ContextBudget.TrimResult trimmed = ContextBudget.trim(history, maxMessages, tokenBudget);
                window = new ArrayList<>(trimmed.messages());
                dropped = trimmed.droppedCount();
                approx = trimmed.approxTokens();
            }
        } else {
            ContextBudget.TrimResult trimmed = ContextBudget.trim(history, maxMessages, tokenBudget);
            window = new ArrayList<>(trimmed.messages());
            dropped = trimmed.droppedCount();
            approx = trimmed.approxTokens();
        }

        window.add(new UserMessage(prompt));
        approx = ContextBudget.approxTokens(window);

        String answer = registry.plainClient(provider)
            .prompt()
            .messages(window)
            .call()
            .content();

        store.appendTurn(id, prompt, answer == null ? "" : answer);

        List<Message> after = store.snapshot(id);
        return new ContextChatResult(
            id,
            strategy.name().toLowerCase(),
            answer,
            after.size(),
            window.size(),
            approx,
            dropped,
            summary
        );
    }

    /**
     * 查看会话原始消息。
     *
     * @param sessionId 会话 id
     * @return 视图列表
     */
    public List<InMemoryChatSessionStore.MessageView> session(String sessionId) {
        return store.views(sessionId);
    }

    /**
     * 清空会话。
     *
     * @param sessionId 会话 id
     * @return 是否删除成功
     */
    public boolean clear(String sessionId) {
        return store.clear(sessionId);
    }

    /**
     * 对旧轮次进行 summarize。
     * @param oldTurns 旧轮次
     * @param provider LLM 提供商
     * @return 摘要
     */
    private String summarize(List<Message> oldTurns, String provider) {
        StringBuilder sb = new StringBuilder();
        for (Message message : oldTurns) {
            sb.append(InMemoryChatSessionStore.roleOf(message))
                .append(": ")
                .append(InMemoryChatSessionStore.textOf(message))
                .append("\n");
        }
        String content = registry.plainClient(provider)
            .prompt()
            .system("把下列对话压缩成简洁中文摘要，保留关键事实与约定，不要编造。")
            .user(sb.toString())
            .call()
            .content();
        return content == null ? "" : content;
    }

    /**
     * @param sessionId        会话 id
     * @param strategy         策略名
     * @param content          模型回复
     * @param rawMessageCount  存储中的原始消息数
     * @param sentMessageCount 实际送给模型的消息数（含本轮 user）
     * @param approxTokens     近似 token
     * @param droppedCount     trim 丢掉条数
     * @param summary          summarize 时的摘要；trim 时为 null
     */
    public record ContextChatResult(
        String sessionId,
        String strategy,
        String content,
        int rawMessageCount,
        int sentMessageCount,
        int approxTokens,
        int droppedCount,
        String summary
    ) {}
}
