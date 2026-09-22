package com.feike.ai.production.secret.manager;

import com.feike.ai.production.modelsettings.model.GlobalProviderVO;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionModelFactoryTest {

    @Test
    void chatAndEmbeddingShouldReadCapabilityRoutesFromModelSettings() {
        SecretResolver secrets = mock(SecretResolver.class);
        when(secrets.available()).thenReturn(true);
        when(secrets.get("llm.gateway")).thenReturn(Optional.of("test-key"));
        GlobalModelSettingsService settings = mock(GlobalModelSettingsService.class);
        when(settings.provider("gateway")).thenReturn(provider());
        when(settings.route("chat")).thenReturn(new ModelRouteVO("chat", "gateway", "chat-routed", null));
        when(settings.route("embedding")).thenReturn(new ModelRouteVO("embedding", "gateway", "embed-routed", null));

        ProductionModelFactory factory = new ProductionModelFactory(secrets, settings);

        assertNotNull(factory.chatModel(null));
        assertNotNull(factory.embeddingModel(1024));
        verify(settings).route("chat");
        verify(settings).route("embedding");
    }

    @Test
    void missingRouteShouldFailInsteadOfFallingBackToYaml() {
        ProductionModelFactory factory = new ProductionModelFactory(
            mock(SecretResolver.class), mock(GlobalModelSettingsService.class));

        assertThrows(BusinessException.class, () -> factory.chatModel(null));
    }

    private static GlobalProviderVO provider() {
        return new GlobalProviderVO(
            "gateway", "企业网关", "https://llm.example.test/v1", "provider-default",
            List.of("provider-default", "chat-routed", "embed-routed"),
            List.of("chat", "embedding"), 0.2, false, true, true);
    }
}
