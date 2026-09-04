package com.feike.ai.samples.chat;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;

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
     * 同步聊天结果：正文与可选 token 用量。
     *
     * @param content 模型完整回复
     * @param usage   网关返回的用量，未上报时为 {@code null}
     */
    public record ChatResult(String content, TokenUsage usage) {}

    /**
     * 一次性返回完整回复。
     *
     * @param prompt      用户输入
     * @param temperature 为空则用工厂默认温度；Spring AI 2.0 的 {@code options()} 要传 Builder 而非已 build 的 Options
     * @param provider    Provider id，空则用默认 DeepSeek
     * @return 回复正文与 token 用量
     */
    public ChatResult chat(String prompt, Double temperature, String provider) {
        String resolved = registry.resolveProviderId(provider);
        ChatClient chatClient = registry.plainClient(provider);
        // 1. 构建 Prompt
        var spec = chatClient.prompt().system(systemPrompt).user(prompt);
        // 2. 可选温度：须合并 Provider extraBody，否则会冲掉 enable_thinking
        if (temperature != null) {
            var optionsBuilder = OpenAiChatOptions.builder().temperature(temperature);
            AiProperties.Provider cfg = registry.providerConfig(resolved);
            if (cfg != null && cfg.enableThinking() != null) {
                optionsBuilder.extraBody(Map.of(
                    "chat_template_kwargs",
                    Map.of("enable_thinking", cfg.enableThinking())
                ));
            }
            spec = spec.options(optionsBuilder);
        }
        var call = spec.call();
        // CallResponseSpec 只能消费一次：勿 content() 后再 chatResponse()
        var chatResponse = call.chatResponse();
        String content = "";
        if (chatResponse != null
            && chatResponse.getResult() != null
            && chatResponse.getResult().getOutput() != null
            && chatResponse.getResult().getOutput().getText() != null) {
            content = chatResponse.getResult().getOutput().getText();
        }
        return new ChatResult(content, TokenUsageExtractor.from(chatResponse));
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
