package com.feike.ai.samples.agent;

import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 以 ReAct 框架为模板，自行实现显式循环，对比 {@code ChatClient.tools(...).call()} 的框架托管循环。
 * 显式 ReAct Loop：Perceive → Reason → Act → Observe。
 * <p>
 * Spring AI 2.0 的 {@code ChatModel.call()} 只返回 tool_calls，不自动执行工具；
 * 本类自行执行、限制 {@code maxSteps}，对照 {@code ChatClient.tools(...).call()} 的框架托管循环。
 * <p>
 * 第十期：同步与流式共用完整多跳循环；可选 {@link Progress} 推送逐步 tool 事件并累加 {@link TokenUsage}。
 */
public final class ReactAgentLoop {

    /**
     * 单步轨迹，便于对照模型为何调用了哪个工具。
     *
     * @param index          从 1 开始的轮次
     * @param assistantText  该轮模型文本（可能为空，只有 tool_calls）
     * @param toolName       工具名
     * @param toolArgs       模型给出的 JSON 参数
     * @param toolResult     本地执行结果或错误摘要
     */
    public record Step(int index, String assistantText, String toolName, String toolArgs, String toolResult) {}

    /**
     * 一次 Agent 运行的结果。
     *
     * @param finalAnswer      最终给用户的文本；触达步数上限时为熔断说明
     * @param steps            已执行的工具步骤
     * @param reachedMaxSteps  是否因 maxSteps 强制结束
     * @param usage            各轮 LLM call 累加用量；网关未返回时为 {@code null}
     * @param usageCalls       计入用量的 LLM 调用次数
     */
    public record Trace(
        String finalAnswer,
        List<Step> steps,
        boolean reachedMaxSteps,
        TokenUsage usage,
        int usageCalls
    ) {}

    /**
     * 循环进度回调（流式 SSE 用）；同步 {@link #run} 可传 {@code null}。
     */
    public interface Progress {
        /** 即将执行 / 已解析到某次 tool_call。 */
        void onToolCall(int index, String assistantText, String toolName, String toolArgs);

        /** 工具已执行完毕。 */
        void onToolResult(int index, String toolName, String toolResult);

        /**
         * 单次 LLM call 后的用量快照。
         *
         * @param callUsage 本轮用量，可能为 null
         * @param calls     已累计调用次数
         * @param totalUsage 累计用量
         */
        default void onLlmUsage(TokenUsage callUsage, int calls, TokenUsage totalUsage) {}
    }

    private ReactAgentLoop() {}

    /**
     * 运行直到模型不再请求工具，或达到步数上限。
     *
     * @param chatModel    底层聊天模型
     * @param toolObject   带 {@code @Tool} 的实例，通常是 {@code DemoTools}
     * @param systemPrompt 角色与工具使用约束
     * @param userPrompt   用户任务
     * @param maxSteps     熔断步数，至少为 1
     * @return 最终答案与工具轨迹
     */
    public static Trace run(
        ChatModel chatModel,
        Object toolObject,
        String systemPrompt,
        String userPrompt,
        int maxSteps
    ) {
        return run(chatModel, toolObject, systemPrompt, userPrompt, maxSteps, null);
    }

    /**
     * 完整多跳 ReAct；{@code progress} 非空时逐步回调（供 SSE）。
     *
     * @param progress 可为 {@code null}
     */
    public static Trace run(
        ChatModel chatModel,
        Object toolObject,
        String systemPrompt,
        String userPrompt,
        int maxSteps,
        Progress progress
    ) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(toolObject)
            .build()
            .getToolCallbacks();
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        Arrays.stream(callbacks).forEach(cb -> byName.put(cb.getToolDefinition().name(), cb));

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        List<Step> steps = new ArrayList<>();
        TokenUsage usageAcc = null;
        int usageCalls = 0;
        int limit = Math.max(1, maxSteps);
        for (int i = 1; i <= limit; i++) {
            ChatResponse response = chatModel.call(new Prompt(messages, toolOptions(chatModel, callbacks)));
            TokenUsage callUsage = TokenUsageExtractor.from(response);
            usageAcc = TokenUsageExtractor.sum(usageAcc, callUsage);
            usageCalls++;
            if (progress != null) {
                progress.onLlmUsage(callUsage, usageCalls, usageAcc);
            }

            AssistantMessage assistant = Objects.requireNonNull(response.getResult()).getOutput();
            messages.add(assistant);

            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls.isEmpty()) {
                return new Trace(textOf(assistant), List.copyOf(steps), false, usageAcc, usageCalls);
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : toolCalls) {
                if (progress != null) {
                    progress.onToolCall(i, textOf(assistant), call.name(), call.arguments());
                }
                String result = executeTool(byName, call);
                steps.add(new Step(i, textOf(assistant), call.name(), call.arguments(), result));
                if (progress != null) {
                    progress.onToolResult(i, call.name(), result);
                }
                toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        return new Trace(
            "已达到最大步数 " + limit + "，已停止以防无限循环。",
            List.copyOf(steps),
            true,
            usageAcc,
            usageCalls
        );
    }

    /** 工具失败返回错误文本而不是抛出，让模型有机会换工具或直接回答。 */
    private static String executeTool(Map<String, ToolCallback> byName, AssistantMessage.ToolCall call) {
        ToolCallback callback = byName.get(call.name());
        if (callback == null) {
            return "unknown tool: " + call.name();
        }
        try {
            return callback.call(call.arguments());
        } catch (Exception e) {
            return "tool error: " + e.getMessage();
        }
    }

    /**
     * 为 {@code OpenAiChatModel.call()} 准备工具选项。
     * <p>
     * 必须使用 {@link OpenAiChatOptions}：Spring AI 2.0 的 {@code OpenAiChatModel#createRequest}
     * 会把 Prompt options 强转为 {@code OpenAiChatOptions}；
     * {@code ToolCallingChatOptions.builder()} 产出的是 {@code DefaultToolCallingChatOptions}，会触发 ClassCastException。
     * 同时从 ChatModel 默认 options 复制 model/temperature，因 Prompt 带 options 时不会再与默认配置合并。
     *
     * @param chatModel 用于读取默认 OpenAiChatOptions
     * @param callbacks 已注册的工具回调
     * @return OpenAi 兼容的 ChatOptions
     */
    private static ChatOptions toolOptions(ChatModel chatModel, ToolCallback[] callbacks) {
        List<ToolCallback> callbackList = Arrays.asList(callbacks);
        ChatOptions defaults = chatModel.getOptions();
        if (defaults instanceof OpenAiChatOptions openAiDefaults) {
            return openAiDefaults.mutate().toolCallbacks(callbackList).build();
        }
        return OpenAiChatOptions.builder().toolCallbacks(callbackList).build();
    }

    /**
     * 从 AssistantMessage 中提取纯文本，可能为空。
     *
     * @param message 模型回复
     * @return 文本内容
     */
    private static String textOf(AssistantMessage message) {
        String text = message.getText();
        return text == null ? "" : text;
    }
}
