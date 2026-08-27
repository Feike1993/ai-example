package com.feike.ai.samples.chat;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * Chat 样例：同步补全与 token 流，对应学习路径里的 Token / 采样参数 / TTFT。
 */
@Service
public class ChatSampleService {

    private final LlmProviderRegistry registry;
    private final String systemPrompt;

    /**
     * @param registry     按请求选择 Provider
     * @param promptLoader 加载 {@code prompts/chat-assistant.st}
     */
    public ChatSampleService(LlmProviderRegistry registry, PromptLoader promptLoader) throws IOException {
        this.registry = registry;
        this.systemPrompt = promptLoader.load("chat-assistant.st");
    }

    /**
     * 一次性返回完整回复。
     *
     * @param prompt      用户输入
     * @param temperature 为空则用工厂默认温度；Spring AI 2.0 的 {@code options()} 要传 Builder 而非已 build 的 Options
     * @param provider    Provider id，空则用默认 DeepSeek
     * @return 模型文本
     */
    public String chat(String prompt, Double temperature, String provider) {
        ChatClient chatClient = registry.plainClient(provider);
        // 1. 构建 Prompt
        var spec = chatClient.prompt().system(systemPrompt).user(prompt);
        // 2. 可选：设置温度参数
        if (temperature != null) {
            spec = spec.options(OpenAiChatOptions.builder().temperature(temperature));
        }
        return spec.call().content();
    }

    /**
     * 按 token 流式输出，用于观察 TTFT。
     *
     * @param prompt   用户输入
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 增量文本流
     */
    public Flux<String> stream(String prompt, String provider) {
        return registry.plainClient(provider)
            .prompt()
            .system(systemPrompt)
            .user(prompt)
            .stream()
            .content();
    }
}
