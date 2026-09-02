package com.feike.ai.samples.agent;

import com.feike.ai.core.TokenUsage;
import com.feike.ai.samples.tools.DemoTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReactAgentLoop}：终止条件、多跳、Progress 顺序、usage 累加。
 */
@DisplayName("ReactAgentLoop")
class ReactAgentLoopTest {

    @Test
    void shouldStopWhenModelReturnsPlainText() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("北京晴天", 10, 5));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "北京天气?", 8);

        assertEquals("北京晴天", trace.finalAnswer());
        assertFalse(trace.reachedMaxSteps());
        assertTrue(trace.steps().isEmpty());
        assertEquals(1, trace.usageCalls());
        assertEquals(10, trace.usage().prompt());
        assertEquals(5, trace.usage().completion());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void shouldExecuteToolThenFinish() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(toolResponse("add", "{\"a\":3,\"b\":5}", 8, 4))
            .thenReturn(textResponse("结果是 8", 12, 6));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "3+5", 8);

        assertEquals("结果是 8", trace.finalAnswer());
        assertEquals(1, trace.steps().size());
        assertEquals("add", trace.steps().getFirst().toolName());
        assertTrue(trace.steps().getFirst().toolResult().contains("8"));
        assertFalse(trace.reachedMaxSteps());
        assertEquals(2, trace.usageCalls());
        assertEquals(20, trace.usage().prompt());
        assertEquals(10, trace.usage().completion());
    }

    @Test
    void shouldSupportMultiHopTools() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(toolResponse("getWeather", "{\"city\":\"北京\"}", 5, 2))
            .thenReturn(toolResponse("add", "{\"a\":3,\"b\":5}", 6, 2))
            .thenReturn(textResponse("晴，和为 8", 7, 3));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "天气再算 3+5", 8);

        assertEquals(2, trace.steps().size());
        assertEquals("getWeather", trace.steps().get(0).toolName());
        assertEquals("add", trace.steps().get(1).toolName());
        assertEquals(3, trace.usageCalls());
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void shouldStopAtMaxSteps() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(toolResponse("add", "{\"a\":1,\"b\":1}", 1, 1));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "loop", 2);

        assertTrue(trace.reachedMaxSteps());
        assertEquals(2, trace.steps().size());
        assertEquals(2, trace.usageCalls());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void progressShouldSeeToolCallThenResult() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(toolResponse("add", "{\"a\":3,\"b\":5}", 2, 1))
            .thenReturn(textResponse("8", 2, 1));

        List<String> events = new ArrayList<>();
        ReactAgentLoop.Progress progress = new ReactAgentLoop.Progress() {
            @Override
            public void onToolCall(int index, String assistantText, String toolName, String toolArgs) {
                events.add("call:" + toolName);
            }

            @Override
            public void onToolResult(int index, String toolName, String toolResult) {
                events.add("result:" + toolName);
            }
        };

        ReactAgentLoop.run(chatModel, new DemoTools(), "sys", "3+5", 8, progress);

        assertEquals(List.of("call:add", "result:add"), events);
    }

    private static ChatResponse textResponse(String text, int promptTokens, int completionTokens) {
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
        return withUsage(response, promptTokens, completionTokens);
    }

    private static ChatResponse toolResponse(String name, String arguments, int promptTokens, int completionTokens) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", name, arguments);
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();
        return withUsage(new ChatResponse(List.of(new Generation(message))), promptTokens, completionTokens);
    }

    private static ChatResponse withUsage(ChatResponse base, int promptTokens, int completionTokens) {
        return ChatResponse.builder()
            .generations(base.getResults())
            .metadata(ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build())
            .build();
    }
}
