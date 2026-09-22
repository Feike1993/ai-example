package com.feike.ai.core;

import com.feike.ai.core.model.ProviderVO;
import com.feike.ai.production.modelsettings.model.GlobalProviderVO;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 教学场模型注册表：对既有样例保留稳定接口，底层统一委托数据库模型工厂。
 * Provider、模型、调用参数、能力路由和密钥均来自“模型与服务设置”。
 */
@Component
public class LlmProviderRegistry {

    private final GlobalModelSettingsService settings;
    private final ProductionModelFactory models;
    private final int embeddingDimensions;
    private final EmbeddingModel embeddingModel;

    public LlmProviderRegistry(
        GlobalModelSettingsService settings,
        ProductionModelFactory models,
        PgVectorStoreProperties vectorStoreProperties
    ) {
        this.settings = settings;
        this.models = models;
        this.embeddingDimensions = vectorStoreProperties.getDimensions();
        if (embeddingDimensions < 1) {
            throw new IllegalStateException("spring.ai.vectorstore.pgvector.dimensions 必须显式配置");
        }
        this.embeddingModel = new SettingsEmbeddingModel(models, embeddingDimensions);
    }

    /** 列出数据库中的 Provider，供教学场下拉框使用；密钥不回显。 */
    public List<ProviderVO> list() {
        return settings.list().providers().stream()
            .map(provider -> new ProviderVO(
                provider.id(), provider.label(), provider.model(),
                provider.keyConfigured(), provider.capabilities()))
            .toList();
    }

    /** chat 能力路由即教学场默认 Provider。 */
    public String defaultProviderId() {
        return requiredRoute("chat").providerId();
    }

    /** 返回数据库 Provider 参数，不包含 API Key。 */
    public GlobalProviderVO providerConfig(String providerId) {
        String id = resolveProviderId(providerId);
        return settings.provider(id);
    }

    public ChatClient plainClient(String providerId) {
        return models.plainClient(normalizeProviderOverride(providerId));
    }

    public ChatModel chatModel(String providerId) {
        return models.chatModel(normalizeProviderOverride(providerId));
    }

    /** 教学 RAG 每次调用都委托当前 embedding 路由，设置保存并清缓存后立即生效。 */
    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    public String embeddingProviderId() {
        return requiredRoute("embedding").providerId();
    }

    public String resolveProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return defaultProviderId();
        }
        String id = providerId.trim();
        if (settings.provider(id) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 LLM Provider: " + id);
        }
        return id;
    }

    private String normalizeProviderOverride(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return null;
        }
        return resolveProviderId(providerId);
    }

    private ModelRouteVO requiredRoute(String capability) {
        ModelRouteVO route = settings.route(capability);
        if (route == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "模型与服务设置缺少 " + capability + " 能力路由"
            );
        }
        return route;
    }

    private record SettingsEmbeddingModel(ProductionModelFactory models, int dimensions)
        implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return models.embeddingModel(dimensions).call(request);
        }

        @Override
        public float[] embed(Document document) {
            return models.embeddingModel(dimensions).embed(document);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }
}
