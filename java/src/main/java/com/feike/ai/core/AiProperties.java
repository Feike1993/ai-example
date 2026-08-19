package com.feike.ai.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 接入与样例行为配置，绑定 {@code app.ai.*}。
 * <p>
 * 多 Provider 对齐 interview-guide：按 id 选网关，默认 DeepSeek。
 *
 * @param defaultProvider 未传 provider 时使用的 id
 * @param temperature     全局默认采样温度；Provider 未单独配置时用此值
 * @param providers       OpenAI 兼容网关列表，key 为 provider id
 * @param structured      结构化输出重试策略
 * @param agent           Agent Loop 步数上限
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
    String defaultProvider,
    Double temperature,
    Map<String, Provider> providers,
    Structured structured,
    Agent agent
) {
    public AiProperties {
        if (defaultProvider == null || defaultProvider.isBlank()) {
            defaultProvider = "deepseek";
        }
        if (temperature == null) {
            temperature = 0.2;
        }
        if (providers == null) {
            providers = new LinkedHashMap<>();
        }
        if (structured == null) {
            structured = new Structured(2, true, true, true, 200, false);
        }
        if (agent == null) {
            agent = new Agent(8);
        }
    }

    /**
     * 单个 OpenAI 兼容网关。
     *
     * @param label       前端展示名；空则用 provider id
     * @param baseUrl     网关地址，可带或不带 {@code /v1}
     * @param apiKey      调用密钥
     * @param model       聊天模型名
     * @param temperature 覆盖全局温度；为空则用 {@link AiProperties#temperature()}
     */
    public record Provider(
        String label,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature
    ) {}

    /**
     * 结构化输出的重试与修复开关。
     *
     * @param maxAttempts                         至少为 1；含首次调用
     * @param includeLastError                    重试 prompt 是否带上上次解析错误
     * @param retryUseRepairPrompt                失败后是否改写 system prompt
     * @param retryAppendStrictJsonInstruction    是否追加「只返回 JSON」约束
     * @param errorMessageMaxLength               写入 prompt 的错误摘要上限，避免冲淡指令
     * @param schemaValidationEnabled             是否走 Spring AI 的 schema 校验（默认关，兼容性更好）
     */
    public record Structured(
        int maxAttempts,
        boolean includeLastError,
        boolean retryUseRepairPrompt,
        boolean retryAppendStrictJsonInstruction,
        int errorMessageMaxLength,
        boolean schemaValidationEnabled
    ) {
        public Structured {
            if (maxAttempts < 1) {
                maxAttempts = 1;
            }
            if (errorMessageMaxLength < 20) {
                errorMessageMaxLength = 20;
            }
        }
    }

    /**
     * Agent Loop 熔断配置。
     *
     * @param maxSteps 最大推理-行动轮次，防止工具调用死循环
     */
    public record Agent(int maxSteps) {
        public Agent {
            if (maxSteps < 1) {
                maxSteps = 1;
            }
        }
    }
}
