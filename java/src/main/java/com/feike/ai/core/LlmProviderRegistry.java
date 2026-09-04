package com.feike.ai.core;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 provider id 创建并缓存 OpenAI 兼容的 {@link ChatClient} / {@link ChatModel} / {@link EmbeddingModel}。
 * <p>
 * 配置只来自 {@code app.ai.providers}。{@link #plainClient(String)} 不挂默认工具：
 * 结构化输出若混入 tool 消息会污染 JSON。Embedding 与 Chat 分离，见 {@link #embeddingModel()}。
 */
@Component
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final AiProperties properties;
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, ChatClient> plainClientCache = new ConcurrentHashMap<>();
    private volatile EmbeddingModel embeddingModelCache;

    /**
     * @param properties {@code app.ai}；缺 key 时仍允许启动，便于跑单测
     */
    public LlmProviderRegistry(AiProperties properties) {
        this.properties = properties;
        String defaultId = properties.defaultProvider();
        log.info(
            "LlmProviderRegistry ready: defaultProvider={}, providers={}",
            defaultId,
            properties.providers().keySet()
        );
        AiProperties.Provider def = properties.providers().get(defaultId);
        if (def == null) {
            log.warn("默认 Provider '{}' 未在 app.ai.providers 中配置", defaultId);
        } else if (def.apiKey() == null || def.apiKey().isBlank()) {
            log.warn("默认 Provider '{}' 未配置 API Key，真实 LLM 调用会失败", defaultId);
        }
    }

    /**
     * 列出可切换的 Provider，供前端下拉框使用。
     */
    public List<ProviderView> list() {
        List<ProviderView> views = new ArrayList<>();
        for (Map.Entry<String, AiProperties.Provider> entry : properties.providers().entrySet()) {
            AiProperties.Provider cfg = entry.getValue();
            String id = entry.getKey();
            String label = cfg.label() == null || cfg.label().isBlank() ? id : cfg.label();
            boolean configured = cfg.apiKey() != null && !cfg.apiKey().isBlank();
            views.add(new ProviderView(id, label, cfg.model(), configured));
        }
        return views;
    }

    /**
     * @return 配置中的默认 provider id
     */
    public String defaultProviderId() {
        return properties.defaultProvider();
    }

    /**
     * 返回已解析 Provider 的静态配置（不含密钥用途的对外暴露；仅供同进程合并 options）。
     *
     * @param providerId 已 {@link #resolveProviderId(String)} 的 id，或原始 id
     * @return 配置；未知 id 时为 {@code null}
     */
    public AiProperties.Provider providerConfig(String providerId) {
        String id = resolveProviderId(providerId);
        return properties.providers().get(id);
    }

    /**
     * 返回不挂默认 Tools 的 ChatClient。
     *
     * @param providerId 空或 {@code default} 时回退到默认 Provider
     */
    public ChatClient plainClient(String providerId) {
        String id = resolveProviderId(providerId);
        return plainClientCache.computeIfAbsent(id, this::createPlainClient);
    }

    /**
     * 暴露底层 ChatModel，供显式 ReAct Loop 自行执行 tool_calls。
     *
     * @param providerId 空或 {@code default} 时回退到默认 Provider
     */
    public ChatModel chatModel(String providerId) {
        String id = resolveProviderId(providerId);
        return chatModelCache.computeIfAbsent(id, this::buildChatModel);
    }

    /**
     * 返回 Embedding 模型（固定使用 {@code app.ai.embedding-provider}，默认 DashScope）。
     * <p>
     * DeepSeek 等聊天网关往往无 Embedding；RAG 不要跟 Chat Provider 混用同一个 model 字段。
     *
     * @return OpenAI 兼容 EmbeddingModel
     */
    public EmbeddingModel embeddingModel() {
        EmbeddingModel cached = embeddingModelCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (embeddingModelCache == null) {
                embeddingModelCache = buildEmbeddingModel(properties.embeddingProvider());
            }
            return embeddingModelCache;
        }
    }

    /**
     * @return 配置的 Embedding Provider id
     */
    public String embeddingProviderId() {
        return properties.embeddingProvider();
    }

    /**
     * 把请求里的 provider 解析成已配置的 id。
     *
     * @param providerId 请求传入值，可为空  例如： deepseek -> deepseek  dashscope-> dashscope  可配置可调整
     * @return 实际使用的 id
     * @throws ResponseStatusException 未知 id
     */
    public String resolveProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return properties.defaultProvider();
        }
        String id = providerId.trim();
        if (!properties.providers().containsKey(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 LLM Provider: " + id);
        }
        return id;
    }

    /**
     * 创建不挂默认 Tools 的 ChatClient。
     * @param providerId 配置文件中的 provider id
     * @return ChatClient 实例
     */
    private ChatClient createPlainClient(String providerId) {
        return ChatClient.builder(chatModelCache.computeIfAbsent(providerId, this::buildChatModel)).build();
    }

    /**
     * 创建底层 ChatModel。
     * @param providerId 配置文件中的 provider id
     * @return ChatModel 实例
     */
    private OpenAiChatModel buildChatModel(String providerId) {
        AiProperties.Provider cfg = properties.providers().get(providerId);
        if (cfg == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知 LLM Provider: " + providerId);
        }
        String apiKey = cfg.apiKey() == null ? "" : cfg.apiKey();
        if (apiKey.isBlank()) {
            log.warn("Provider '{}' 未配置 API Key，进程可继续，但真实调用会失败", providerId);
        }
        boolean bypassProxy = Boolean.TRUE.equals(cfg.bypassProxy());
        // 创建 OpenAiClient 实例
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(
            cfg.baseUrl(),
            apiKey.isBlank() ? "missing-key" : apiKey,
            bypassProxy
        );
        // temperature的作用 是控制生成结果的随机性，值越小结果越确定，值越大结果越随机
        Double temperature = cfg.temperature() != null ? cfg.temperature() : properties.temperature();
        // 创建 OpenAiChatOptions 实例
        var optionsBuilder = OpenAiChatOptions.builder()
            .model(cfg.model())
            .temperature(temperature);
        // Qwen3.5/vLLM：默认 thinking 会占满 token 且 content=null；显式关掉才能被 Spring AI 读到正文
        if (cfg.enableThinking() != null) {
            optionsBuilder.extraBody(Map.of(
                "chat_template_kwargs",
                Map.of("enable_thinking", cfg.enableThinking())
            ));
        }
        OpenAiChatOptions options = optionsBuilder.build();
        log.info(
            "Building ChatModel provider={} baseUrl={} model={} enableThinking={} bypassProxy={}",
            providerId,
            cfg.baseUrl(),
            cfg.model(),
            cfg.enableThinking(),
            bypassProxy
        );
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async()) // 添加 async 客户端 作用：提高请求处理效率
            .options(options)
            .build();
    }

    /**
     * 按 Embedding Provider 创建 EmbeddingModel（模型名取自 {@code app.ai.embedding}，非 Chat model）。
     *
     * @param providerId 通常为 dashscope
     * @return EmbeddingModel
     */
    private EmbeddingModel buildEmbeddingModel(String providerId) {
        AiProperties.Provider cfg = properties.providers().get(providerId);
        if (cfg == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "未知 Embedding Provider: " + providerId + "（请在 app.ai.providers 中配置）"
            );
        }
        String apiKey = cfg.apiKey() == null ? "" : cfg.apiKey();
        if (apiKey.isBlank()) {
            log.warn("Embedding Provider '{}' 未配置 API Key，RAG ingest/query 会失败", providerId);
        }
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(
            cfg.baseUrl(),
            apiKey.isBlank() ? "missing-key" : apiKey,
            Boolean.TRUE.equals(cfg.bypassProxy())
        );
        AiProperties.Embedding emb = properties.embedding();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(emb.model())
            .dimensions(emb.dimensions())
            .build();
        log.info(
            "Building EmbeddingModel provider={} baseUrl={} model={} dimensions={}",
            providerId,
            cfg.baseUrl(),
            emb.model(),
            emb.dimensions()
        );
        return OpenAiEmbeddingModel.builder()
            .openAiClient(openAiClient)
            .options(options)
            .build();
    }

    /**
     * 前端 / 索引用的 Provider 摘要，不含密钥。
     *
     * @param id          配置 key
     * @param label       展示名
     * @param model       当前模型
     * @param configured  是否已填 API Key
     */
    public record ProviderView(String id, String label, String model, boolean configured) {}
}
