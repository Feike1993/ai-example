package com.feike.ai.production.secret.manager;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.ApiPathResolver;
import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    private final Map<String, OpenAiChatModel> cache = new ConcurrentHashMap<>();

    /**
     * @param aiProperties 取 baseUrl / model，不取明文 key
     * @param secrets      信封解密后的 key
     */
    public ProductionModelFactory(AiProperties aiProperties, SecretResolver secrets) {
        this.aiProperties = aiProperties;
        this.secrets = secrets;
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
        return cache.computeIfAbsent(id, this::build);
    }

    private String resolveId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return aiProperties.defaultProvider();
        }
        return providerId.trim();
    }

    private OpenAiChatModel build(String providerId) {
        if (!secrets.available()) {
            throw new SecretUnavailableException("工业级密钥不可用，无法创建 ChatModel");
        }
        AiProperties.Provider cfg = aiProperties.providers().get(providerId);
        if (cfg == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 LLM Provider: " + providerId);
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
        var optionsBuilder = OpenAiChatOptions.builder()
            .model(cfg.model())
            .temperature(temperature);
        if (cfg.enableThinking() != null) {
            optionsBuilder.extraBody(Map.of(
                "chat_template_kwargs",
                Map.of("enable_thinking", cfg.enableThinking())
            ));
        }
        log.info("生产 ChatModel provider={} model={}", providerId, cfg.model());
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(optionsBuilder.build())
            .build();
    }
}
