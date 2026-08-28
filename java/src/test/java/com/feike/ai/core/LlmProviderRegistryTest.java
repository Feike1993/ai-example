package com.feike.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Provider id 解析与清单，不真正连接网关。
 */
@DisplayName("LlmProviderRegistry")
class LlmProviderRegistryTest {

    @Test
    void shouldFallbackBlankAndDefaultAliasToDeepSeek() {
        LlmProviderRegistry registry = registry();
        assertEquals("deepseek", registry.resolveProviderId(null));
        assertEquals("deepseek", registry.resolveProviderId(" "));
        assertEquals("deepseek", registry.resolveProviderId("default"));
        assertEquals("dashscope", registry.resolveProviderId("dashscope"));
    }

    @Test
    void shouldRejectUnknownProvider() {
        LlmProviderRegistry registry = registry();
        assertThrows(ResponseStatusException.class, () -> registry.resolveProviderId("openai"));
    }

    @Test
    void shouldListConfiguredFlagWithoutExposingKey() {
        LlmProviderRegistry registry = registry();
        List<LlmProviderRegistry.ProviderView> views = registry.list();
        assertEquals(2, views.size());
        assertEquals("deepseek", views.get(0).id());
        assertTrue(views.get(0).configured());
        assertEquals("dashscope", views.get(1).id());
        assertFalse(views.get(1).configured());
    }

    private static LlmProviderRegistry registry() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put(
            "deepseek",
            new AiProperties.Provider("DeepSeek", "https://api.deepseek.com", "sk-test", "deepseek-v4-flash", null)
        );
        providers.put(
            "dashscope",
            new AiProperties.Provider("通义", "https://dashscope.aliyuncs.com/compatible-mode", "", "qwen3.5-flash", null)
        );
        AiProperties properties = new AiProperties(
            "deepseek",
            0.2,
            providers,
            new AiProperties.Structured(2, true, true, true, 200, false),
            new AiProperties.Agent(8),
            "dashscope",
            new AiProperties.Embedding("text-embedding-v3", 1024),
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false)),
            new AiProperties.ContextSettings(24, 2000, 6),
            new AiProperties.MultiAgent(4, 6)
        );
        return new LlmProviderRegistry(properties);
    }
}
