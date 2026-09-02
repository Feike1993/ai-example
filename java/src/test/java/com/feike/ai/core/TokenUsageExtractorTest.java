package com.feike.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TokenUsageExtractor} 单测：有效用量、空用量与空响应。
 */
@DisplayName("TokenUsageExtractor")
class TokenUsageExtractorTest {

    @Test
    void shouldReturnNullForNullResponse() {
        assertNull(TokenUsageExtractor.from(null));
    }

    @Test
    void shouldReturnNullForEmptyUsage() {
        ChatResponse response = new ChatResponse(List.of());
        assertNull(TokenUsageExtractor.from(response));
    }

    @Test
    void shouldReturnNullForExplicitEmptyUsage() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(new EmptyUsage()).build();
        ChatResponse response = new ChatResponse(List.of(), metadata);
        assertNull(TokenUsageExtractor.from(response));
    }

    @Test
    void shouldExtractDefaultUsage() {
        DefaultUsage usage = new DefaultUsage(120, 45, 165, null);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        ChatResponse response = new ChatResponse(List.of(), metadata);

        TokenUsage extracted = TokenUsageExtractor.from(response);

        assertEquals(120, extracted.prompt());
        assertEquals(45, extracted.completion());
        assertEquals(165, extracted.total());
    }

    @Test
    void shouldSumUsages() {
        TokenUsage left = new TokenUsage(10, 5, 15);
        TokenUsage right = new TokenUsage(8, 12, 20);
        TokenUsage sum = TokenUsageExtractor.sum(left, right);
        assertEquals(18, sum.prompt());
        assertEquals(17, sum.completion());
        assertEquals(35, sum.total());
        assertEquals(left, TokenUsageExtractor.sum(left, null));
        assertNull(TokenUsageExtractor.sum(null, null));
    }
}
