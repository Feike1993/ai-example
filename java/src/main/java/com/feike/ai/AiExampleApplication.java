package com.feike.ai;

import com.feike.ai.core.AiProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AI Agent 学习样例入口。
 * <p>
 * 排除 Spring AI 的 OpenAI 自动配置，避免和 {@link com.feike.ai.core.LlmProviderRegistry} 各建一套 ChatModel。
 */
@SpringBootApplication(exclude = {
    OpenAiChatAutoConfiguration.class,
    OpenAiEmbeddingAutoConfiguration.class,
    OpenAiImageAutoConfiguration.class,
    OpenAiAudioSpeechAutoConfiguration.class,
    OpenAiAudioTranscriptionAutoConfiguration.class,
    OpenAiModerationAutoConfiguration.class
})
@EnableConfigurationProperties(AiProperties.class)
public class AiExampleApplication {

    /**
     * 启动样例 HTTP 服务。
     * <p>
     * 本地 API Key 请用 {@code ./gradlew bootRun}（会注入仓库根目录 {@code .env}）；
     * IDE 直跑不会自动读 {@code .env}，需自行配置运行环境变量。
     *
     * @param args 命令行参数，当前未使用
     */
    public static void main(String[] args) {
        SpringApplication.run(AiExampleApplication.class, args);
    }
}
