package com.feike.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiPathResolver} 的路径归一化单测，不访问真实网关。
 */
@DisplayName("ApiPathResolver")
class ApiPathResolverTest {

    @Test
    void shouldAppendV1WhenMissing() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ApiPathResolver.resolveVersionedBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/")
        );
    }

    @Test
    void shouldKeepExistingVersion() {
        assertEquals(
            "https://api.openai.com/v1",
            ApiPathResolver.resolveVersionedBaseUrl("https://api.openai.com/v1/")
        );
        assertTrue(ApiPathResolver.baseUrlContainsVersion("http://localhost:1234/v1"));
        assertFalse(ApiPathResolver.baseUrlContainsVersion("http://localhost:11434"));
    }
}
