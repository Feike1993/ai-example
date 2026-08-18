package com.feike.ai.samples.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 显式 ReAct Loop：Perceive → Reason → Act → Observe。
 * <p>
 * Spring AI 2.0 的 {@code ChatModel.call()} 只返回 tool_calls，不自动执行工具；
 * 本类自行执行、限制 {@code maxSteps}，对照 {@code ChatClient.tools(...).call()} 的框架托管循环。
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
     */
    public record Trace(String finalAnswer, List<Step> steps, boolean reachedMaxSteps) {}

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
        int limit = Math.max(1, maxSteps);
        for (int i = 1; i <= limit; i++) {
            ChatResponse response = chatModel.call(new Prompt(messages, toolOptions(callbacks)));
            AssistantMessage assistant = response.getResult().getOutput();
            messages.add(assistant);

            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                return new Trace(textOf(assistant), List.copyOf(steps), false);
            }

            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : toolCalls) {
                String result = executeTool(byName, call);
                steps.add(new Step(i, textOf(assistant), call.name(), call.arguments(), result));
                toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
        }
        return new Trace("已达到最大步数 " + limit + "，已停止以防无限循环。", List.copyOf(steps), true);
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

    private static ToolCallingChatOptions toolOptions(ToolCallback[] callbacks) {
        return ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(callbacks))
            .build();
    }

    private static String textOf(AssistantMessage message) {
        String text = message.getText();
        return text == null ? "" : text;
    }
}
