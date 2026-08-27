package com.feike.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptLoader} 加载 classpath 模板与占位符替换。
 */
@DisplayName("PromptLoader")
class PromptLoaderTest {

    @Test
    void shouldLoadPromptTemplates() throws Exception {
        PromptLoader loader = new PromptLoader(new DefaultResourceLoader());
        assertTrue(loader.load("chat-assistant.st").contains("AI 助手"));
        assertTrue(loader.load("agent-react.st").contains("工具"));
    }

    @Test
    void shouldReplacePlaceholders() throws Exception {
        PromptLoader loader = new PromptLoader(new DefaultResourceLoader());
        String result = loader.load("test-placeholder.st", Map.of("name", "World"));
        assertEquals("Hello World!", result);
    }
}
