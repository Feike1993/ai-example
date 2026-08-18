package com.feike.ai.core;

import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/**
 * 按配置创建 OpenAI 兼容的 {@link ChatClient} / {@link ChatModel}。
 * <p>
 * 第一期只接一个 Provider。{@link #plainClient()} 不挂默认工具：结构化输出若混入 tool 消息会污染 JSON。
 */
@Component
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    private final OpenAiChatModel chatModel;
    private final ChatClient plainClient;

    /**
     * 按配置构建底层 {@link OpenAiChatModel} 与无工具 ChatClient。
     *
     * @param properties {@code app.ai} 配置；apiKey 为空时仍允许进程启动，便于跑单测
     */
    public LlmClientFactory(AiProperties properties) {
        String apiKey = properties.apiKey() == null ? "" : properties.apiKey();
        if (apiKey.isBlank()) {
            log.warn("未配置 AI_API_KEY，进程可启动，但真实 LLM 调用会失败");
        }

        // 官方客户端构造期校验 key 非空；占位串只为让应用能起来，真正调用仍会 401
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(
            properties.baseUrl(),
            apiKey.isBlank() ? "missing-key" : apiKey
        );
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(properties.model())
            .temperature(properties.temperature())
            .build();
        this.chatModel = OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .build();
        this.plainClient = ChatClient.builder(chatModel).build();
        log.info("LlmClientFactory ready: baseUrl={}, model={}", properties.baseUrl(), properties.model());
    }

    /**
     * 返回不挂默认 Tools 的 ChatClient，供 Chat / 结构化输出 / 按请求挂载工具使用。
     *
     * @return 可复用的 ChatClient
     */
    public ChatClient plainClient() {
        return plainClient;
    }

    /**
     * 暴露底层 ChatModel，供显式 ReAct Loop 自行执行 tool_calls。
     *
     * @return 与 {@link #plainClient()} 共享的模型实例
     */
    public ChatModel chatModel() {
        return chatModel;
    }
}
