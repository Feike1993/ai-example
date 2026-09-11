package com.feike.ai.production.secret.manager;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.ApiPathResolver;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产链路专用的 ChatModel 工厂：API Key 只从 {@link SecretResolver} 取。
 * <p>
 * 教学侧 {@code LlmProviderRegistry} 继续读环境变量，两边不抢同一份明文。
 * 密钥解不开时抛 {@link SecretUnavailableException}，由接口变成 503。
 */
public class ProductionModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ProductionModelFactory.class);

    private final AiProperties aiProperties;
    private final SecretResolver secrets;
    private final ProductionProperties production;
    private final Map<String, OpenAiChatModel> cache = new ConcurrentHashMap<>();

    /**
     * @param aiProperties 取 baseUrl / model，不取明文 key
     * @param secrets      信封解密后的 key
     */
    public ProductionModelFactory(AiProperties aiProperties, SecretResolver secrets) {
        this(aiProperties, secrets, null);
    }

    /**
     * @param aiProperties 取 baseUrl / model，不取明文 key
     * @param secrets      信封解密后的 key
     * @param production   视觉模型名；可空则只用 Provider 默认文本模型
     */
    public ProductionModelFactory(
        AiProperties aiProperties,
        SecretResolver secrets,
        ProductionProperties production
    ) {
        this.aiProperties = aiProperties;
        this.secrets = secrets;
        this.production = production;
    }

    /**
     * @param providerId Provider id；空则用默认
     * @return 不挂工具的 ChatClient
     */
    public ChatClient plainClient(String providerId) {
        return ChatClient.builder(chatModel(providerId)).build();
    }

    /**
     * @param providerId Provider id；空则用默认
     * @return ChatModel，供 Agent 循环使用
     */
    public ChatModel chatModel(String providerId) {
        String id = resolveId(providerId);
        return cache.computeIfAbsent(id, key -> build(key, null));
    }

    /**
     * 识图用的 ChatClient：同一把信封 Key，模型名换成视觉模型。
     * <p>
     * 与文本模型分缓存，避免把 {@code qwen-vl-plus} 写进文本 Client 影响后续纯文本问答。
     *
     * @param providerId Provider id；空则用 {@code media.vision-provider}
     * @return 视觉 ChatClient
     */
    public ChatClient visionClient(String providerId) {
        return ChatClient.builder(visionChatModel(providerId)).build();
    }

    /**
     * @param providerId Provider id；空则用视觉默认 Provider
     * @return 视觉 ChatModel
     */
    public ChatModel visionChatModel(String providerId) {
        String visionModel = production == null ? "qwen-vl-plus" : production.media().visionModel();
        String id = resolveVisionId(providerId);
        return cache.computeIfAbsent(id + ":vision:" + visionModel, key -> build(id, visionModel));
    }

    private String resolveId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return aiProperties.defaultProvider();
        }
        return providerId.trim();
    }

    private String resolveVisionId(String providerId) {
        if (providerId != null && !providerId.isBlank()) {
            return providerId.trim();
        }
        if (production != null) {
            return production.media().visionProvider();
        }
        return "dashscope";
    }

    private OpenAiChatModel build(String providerId, String modelOverride) {
        if (!secrets.available()) {
            throw new SecretUnavailableException("工业级密钥不可用，无法创建 ChatModel");
        }
        AiProperties.Provider cfg = aiProperties.providers().get(providerId);
        if (cfg == null) {
            throw new BusinessException(ErrorCodeEnum.UNKNOWN_PROVIDER, "未知 LLM Provider: " + providerId);
        }
        String apiKey = secrets.get(SecretResolver.llmKey(providerId)).orElse("");
        if (apiKey.isBlank()) {
            throw new SecretUnavailableException("prod_secret 中没有 llm." + providerId);
        }
        boolean bypassProxy = Boolean.TRUE.equals(cfg.bypassProxy());
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(
            cfg.baseUrl(),
            apiKey,
            bypassProxy
        );
        Double temperature = cfg.temperature() != null ? cfg.temperature() : aiProperties.temperature();
        String model = modelOverride == null || modelOverride.isBlank() ? cfg.model() : modelOverride;
        var optionsBuilder = OpenAiChatOptions.builder()
            .model(model)
            .temperature(temperature);
        if (cfg.enableThinking() != null) {
            optionsBuilder.extraBody(Map.of(
                "chat_template_kwargs",
                Map.of("enable_thinking", cfg.enableThinking())
            ));
        }
        log.info("生产 ChatModel provider={} model={}", providerId, model);
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(optionsBuilder.build())
            .build();
    }
}
