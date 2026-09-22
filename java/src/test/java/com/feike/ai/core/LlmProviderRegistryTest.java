package com.feike.ai.core;

import com.feike.ai.core.model.ProviderVO;
import com.feike.ai.production.modelsettings.model.GlobalProviderVO;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.model.ModelSettingsVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证教学 Provider 解析和清单全部来自数据库模型设置。 */
@DisplayName("LlmProviderRegistry")
class LlmProviderRegistryTest {

    @Test
    void shouldFallbackBlankAndDefaultAliasToChatRoute() {
        LlmProviderRegistry registry = registry();
        assertEquals("deepseek", registry.resolveProviderId(null));
        assertEquals("deepseek", registry.resolveProviderId(" "));
        assertEquals("deepseek", registry.resolveProviderId("default"));
        assertEquals("dashscope", registry.resolveProviderId("dashscope"));
    }

    @Test
    void shouldRejectUnknownProvider() {
        assertThrows(ResponseStatusException.class,
            () -> registry().resolveProviderId("openai"));
    }

    @Test
    void shouldListDatabaseConfiguredFlagWithoutExposingKey() {
        List<ProviderVO> views = registry().list();
        assertEquals(2, views.size());
        assertEquals("deepseek", views.get(0).id());
        assertTrue(views.get(0).configured());
        assertEquals("dashscope", views.get(1).id());
        assertFalse(views.get(1).configured());
    }

    @Test
    void embeddingProviderShouldComeFromEmbeddingRoute() {
        assertEquals("dashscope", registry().embeddingProviderId());
    }

    private static LlmProviderRegistry registry() {
        GlobalProviderVO deepseek = provider(
            "deepseek", "DeepSeek", "deepseek-v4-flash", List.of("chat", "tools"), true);
        GlobalProviderVO dashscope = provider(
            "dashscope", "通义", "qwen3.8-max", List.of("chat", "embedding"), false);
        GlobalModelSettingsService settings = mock(GlobalModelSettingsService.class);
        when(settings.list()).thenReturn(new ModelSettingsVO(
            List.of(deepseek, dashscope),
            List.of(
                new ModelRouteVO("chat", "deepseek", "deepseek-v4-flash", null),
                new ModelRouteVO("embedding", "dashscope", "text-embedding-v3", null)
            )
        ));
        when(settings.route("chat")).thenReturn(
            new ModelRouteVO("chat", "deepseek", "deepseek-v4-flash", null));
        when(settings.route("embedding")).thenReturn(
            new ModelRouteVO("embedding", "dashscope", "text-embedding-v3", null));
        when(settings.provider("deepseek")).thenReturn(deepseek);
        when(settings.provider("dashscope")).thenReturn(dashscope);
        PgVectorStoreProperties vector = new PgVectorStoreProperties();
        vector.setDimensions(1024);
        return new LlmProviderRegistry(settings, mock(ProductionModelFactory.class), vector);
    }

    private static GlobalProviderVO provider(
        String id, String label, String model, List<String> capabilities, boolean configured
    ) {
        return new GlobalProviderVO(
            id, label, "https://" + id + ".example.test", model, List.of(model), capabilities,
            0.2, null, false, configured);
    }
}
