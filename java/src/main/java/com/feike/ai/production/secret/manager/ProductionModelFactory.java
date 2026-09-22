package com.feike.ai.production.secret.manager;

import com.feike.ai.core.ApiPathResolver;
import com.feike.ai.production.modelsettings.model.GlobalProviderVO;
import com.feike.ai.production.modelsettings.model.ModelRouteVO;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 教学场与工业场共用的数据库模型工厂。Provider、模型、调用参数和能力路由只读
 * 模型设置表，密钥只读 {@link SecretResolver}。
 */
public class ProductionModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ProductionModelFactory.class);

    private final SecretResolver secrets;
    private final GlobalModelSettingsService settings;
    private final Map<String, OpenAiChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiEmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

    public ProductionModelFactory(SecretResolver secrets, GlobalModelSettingsService settings) {
        this.secrets = secrets;
        this.settings = settings;
    }

    public ChatClient plainClient(String providerId) {
        return ChatClient.builder(chatModel(providerId)).build();
    }

    /** 未指定 Provider 时严格使用 chat 能力路由；显式指定时使用该 Provider 的首选模型。 */
    public ChatModel chatModel(String providerId) {
        ModelSelection selection = selection("chat", providerId);
        return chatCache.computeIfAbsent(selection.cacheKey(), key -> buildChat(selection));
    }

    public ChatClient visionClient(String providerId) {
        return ChatClient.builder(visionChatModel(providerId)).build();
    }

    /** 未指定 Provider 时严格使用 vision 能力路由。 */
    public ChatModel visionChatModel(String providerId) {
        ModelSelection selection = selection("vision", providerId);
        return chatCache.computeIfAbsent(selection.cacheKey(), key -> buildChat(selection));
    }

    /** 生产 pgvector 专用 EmbeddingModel，维度沿用向量表 schema 配置。 */
    public EmbeddingModel embeddingModel(int dimensions) {
        ModelSelection selection = selection("embedding", null);
        String key = selection.cacheKey() + ":dimensions:" + dimensions;
        return embeddingCache.computeIfAbsent(key, ignored -> buildEmbedding(selection, dimensions));
    }

    private ModelSelection selection(String capability, String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            GlobalProviderVO provider = requiredProvider(providerId.trim());
            return new ModelSelection(capability, provider, provider.model());
        }
        ModelRouteVO route = settings.route(capability);
        if (route == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "模型与服务设置缺少 " + capability + " 能力路由");
        }
        return new ModelSelection(capability, requiredProvider(route.providerId()), route.model());
    }

    private GlobalProviderVO requiredProvider(String providerId) {
        GlobalProviderVO provider = settings.provider(providerId);
        if (provider == null) {
            throw new BusinessException(ErrorCodeEnum.UNKNOWN_PROVIDER, "未知 LLM Provider: " + providerId);
        }
        return provider;
    }

    private OpenAiChatModel buildChat(ModelSelection selection) {
        GlobalProviderVO provider = selection.provider();
        OpenAIClient client = openAiClient(provider);
        var optionsBuilder = OpenAiChatOptions.builder().model(selection.model());
        if (provider.temperature() != null) {
            optionsBuilder.temperature(provider.temperature());
        }
        if (provider.enableThinking() != null) {
            optionsBuilder.extraBody(Map.of(
                "chat_template_kwargs", Map.of("enable_thinking", provider.enableThinking())
            ));
        }
        log.info("生产 ChatModel capability={} provider={} model={}",
            selection.capability(), provider.id(), selection.model());
        return OpenAiChatModel.builder()
            .openAiClient(client)
            .openAiClientAsync(client.async())
            .options(optionsBuilder.build())
            .build();
    }

    private OpenAiEmbeddingModel buildEmbedding(ModelSelection selection, int dimensions) {
        GlobalProviderVO provider = selection.provider();
        OpenAIClient client = openAiClient(provider);
        var options = OpenAiEmbeddingOptions.builder()
            .model(selection.model())
            .dimensions(dimensions)
            .build();
        log.info("生产 EmbeddingModel provider={} model={} dimensions={}",
            provider.id(), selection.model(), dimensions);
        return OpenAiEmbeddingModel.builder()
            .openAiClient(client)
            .options(options)
            .build();
    }

    private OpenAIClient openAiClient(GlobalProviderVO provider) {
        if (!secrets.available()) {
            throw new SecretUnavailableException("工业级密钥不可用，无法创建模型客户端");
        }
        String apiKey = secrets.get(SecretResolver.llmKey(provider.id())).orElse("");
        if (apiKey.isBlank()) {
            throw new SecretUnavailableException("prod_secret 中没有 llm." + provider.id());
        }
        return ApiPathResolver.buildOpenAiClient(
            provider.baseUrl(), apiKey, provider.bypassProxy());
    }

    /** 管理员保存 Provider 或能力路由后清空本实例缓存。 */
    public void invalidate() {
        chatCache.clear();
        embeddingCache.clear();
    }

    private record ModelSelection(String capability, GlobalProviderVO provider, String model) {
        private String cacheKey() {
            return capability + ':' + provider.id() + ':' + model;
        }
    }
}
