package com.feike.ai.samples.agent;

import com.feike.ai.samples.tools.DemoTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
 * {@link ReactAgentLoop} 的终止条件单测：明文结束、先调工具再结束、触达 maxSteps。
 */
@DisplayName("ReactAgentLoop")
class ReactAgentLoopTest {

    @Test
    void shouldStopWhenModelReturnsPlainText() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("北京晴天"));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "北京天气?", 8);

        assertEquals("北京晴天", trace.finalAnswer());
        assertFalse(trace.reachedMaxSteps());
        assertTrue(trace.steps().isEmpty());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void shouldExecuteToolThenFinish() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(toolResponse("add", "{\"a\":3,\"b\":5}"))
            .thenReturn(textResponse("结果是 8"));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "3+5", 8);

        assertEquals("结果是 8", trace.finalAnswer());
        assertEquals(1, trace.steps().size());
        assertEquals("add", trace.steps().getFirst().toolName());
        assertTrue(trace.steps().getFirst().toolResult().contains("8"));
        assertFalse(trace.reachedMaxSteps());
    }

    @Test
    void shouldStopAtMaxSteps() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(toolResponse("add", "{\"a\":1,\"b\":1}"));

        ReactAgentLoop.Trace trace = ReactAgentLoop.run(
            chatModel, new DemoTools(), "sys", "loop", 2);

        assertTrue(trace.reachedMaxSteps());
        assertEquals(2, trace.steps().size());
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    private static ChatResponse toolResponse(String name, String arguments) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", name, arguments);
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(toolCall))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
