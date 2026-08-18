package com.feike.ai.samples.chat;

import com.feike.ai.core.LlmClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Chat 样例：同步补全与 token 流，对应学习路径里的 Token / 采样参数 / TTFT。
 */
@Service
public class ChatSampleService {

    private final ChatClient chatClient;

    /**
     * @param factory 提供不挂 Tools 的 ChatClient
     */
    public ChatSampleService(LlmClientFactory factory) {
        this.chatClient = factory.plainClient();
    }

    /**
     * 一次性返回完整回复。
     *
     * @param prompt      用户输入
     * @param temperature 为空则用工厂默认温度；Spring AI 2.0 的 {@code options()} 要传 Builder 而非已 build 的 Options
     * @return 模型文本
     */
    public String chat(String prompt, Double temperature) {
        var spec = chatClient.prompt().user(prompt);
        if (temperature != null) {
            spec = spec.options(OpenAiChatOptions.builder().temperature(temperature));
        }
        return spec.call().content();
    }

    /**
     * 按 token 流式输出，用于观察 TTFT。
     *
     * @param prompt 用户输入
     * @return 增量文本流
     */
    public Flux<String> stream(String prompt) {
        return chatClient.prompt()
            .user(prompt)
            .stream()
            .content();
    }
}
