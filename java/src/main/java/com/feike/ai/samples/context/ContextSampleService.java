package com.feike.ai.samples.context;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文工程样例：多轮会话 + trim / summarize 预算策略；存储由 {@link ChatSessionStore} 注入。
 */
@Service
public class ContextSampleService {

    private static final String DEFAULT_SYSTEM = """
        你是助手。根据对话历史回答；若历史不足就如实说明。
        用简体中文回答。
        """;

    private final ChatSessionStore store;
    private final LlmProviderRegistry registry;
    private final AiProperties.ContextSettings contextSettings;

    /**
     * @param store      会话存储（jdbc 或 memory）
     * @param registry   LLM
     * @param properties 读取 context 预算
     */
    public ContextSampleService(
        ChatSessionStore store,
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
            history.add(ChatSessionStore.asSystem(DEFAULT_SYSTEM));
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
            ContextBudget.SummarizePlan plan = ContextBudget.planSummarize(
                history, keepRecent, maxMessages, tokenBudget
            );
            if (plan.needsSummary() && !plan.toSummarize().isEmpty()) {
                summary = summarize(plan.toSummarize(), provider);
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

        var call = registry.plainClient(provider)
            .prompt()
            .messages(window)
            .call();
        String answer = call.content();
        TokenUsage usage = TokenUsageExtractor.from(call.chatResponse());

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
            summary,
            usage,
            store.storeKind()
        );
    }

    /**
     * 查看会话原始消息。
     *
     * @param sessionId 会话 id
     * @return 视图列表
     */
    public List<ChatSessionStore.MessageView> session(String sessionId) {
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
     * @return 当前存储实现标识
     */
    public String storeKind() {
        return store.storeKind();
    }

    private String summarize(List<Message> oldTurns, String provider) {
        StringBuilder sb = new StringBuilder();
        for (Message message : oldTurns) {
            sb.append(ChatSessionStore.roleOf(message))
                .append(": ")
                .append(ChatSessionStore.textOf(message))
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
     * @param store 当前存储实现：{@code jdbc} 或 {@code memory}
     */
    public record ContextChatResult(
        String sessionId,
        String strategy,
        String content,
        int rawMessageCount,
        int sentMessageCount,
        int approxTokens,
        int droppedCount,
        String summary,
        TokenUsage usage,
        String store
    ) {}
}
