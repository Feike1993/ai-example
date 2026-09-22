package com.feike.ai.core;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.model.ProviderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
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
    void shouldBindProviderMapWhenCompatibilityConstructorExists() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("app.ai.default-provider", "deepseek");
        source.put("app.ai.providers.deepseek.label", "DeepSeek");
        source.put("app.ai.providers.deepseek.base-url", "https://api.deepseek.com");
        source.put("app.ai.providers.deepseek.api-key", "sk-test");
        source.put("app.ai.providers.deepseek.model", "deepseek-chat");
        source.put("app.ai.providers.deepseek.capabilities[0]", "chat");
        source.put("app.ai.providers.dashscope.label", "阿里云百炼");
        source.put("app.ai.providers.dashscope.base-url", "https://dashscope.aliyuncs.com/compatible-mode");
        source.put("app.ai.providers.dashscope.api-key", "sk-test");
        source.put("app.ai.providers.dashscope.model", "qwen-max");
        source.put("app.ai.providers.dashscope.capabilities[0]", "embedding");

        AiProperties properties = new Binder(new MapConfigurationPropertySource(source))
            .bind("app.ai", Bindable.of(AiProperties.class))
            .orElseThrow(() -> new AssertionError("app.ai 配置绑定失败"));

        assertEquals(List.of("deepseek", "dashscope"), properties.providers().keySet().stream().toList());
        assertEquals(List.of("embedding"), properties.providers().get("dashscope").capabilities());
    }

    @Test
    void shouldListConfiguredFlagWithoutExposingKey() {
        LlmProviderRegistry registry = registry();
        List<ProviderVO> views = registry.list();
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
            new AiProperties.Provider("DeepSeek", "https://api.deepseek.com", "sk-test", "deepseek-v4-flash", null, null, null)
        );
        providers.put(
            "dashscope",
            new AiProperties.Provider("通义", "https://dashscope.aliyuncs.com/compatible-mode", "", "qwen3.5-flash", null, null, null)
        );
        AiProperties properties = new AiProperties(
            "deepseek",
            0.2,
            providers,
            new AiProperties.Structured(2, true, true, true, 200, false),
            new AiProperties.Agent(8),
            "dashscope",
            new AiProperties.Embedding("text-embedding-v3", 1024),
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            new AiProperties.ContextSettings(24, 2000, 6, "memory"),
            new AiProperties.MultiAgent(4, 6),
            new AiProperties.Memory(4, "demo", 0.92, 5),
            new AiProperties.Mcp("inprocess", "dev-mcp-token"),
            null
        );
        return new LlmProviderRegistry(properties);
    }
}
