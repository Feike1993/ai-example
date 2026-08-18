package com.feike.ai.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入与样例行为配置，绑定 {@code app.ai.*}。
 *
 * @param baseUrl      OpenAI 兼容网关地址；可带或不带 {@code /v1}
 * @param apiKey       调用密钥，对应环境变量 {@code AI_API_KEY}
 * @param model        聊天模型名
 * @param temperature  采样温度，未配置时默认 0.2（偏确定性，便于对照）
 * @param structured   结构化输出重试策略
 * @param agent        Agent Loop 步数上限
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
    String baseUrl,
    String apiKey,
    String model,
    Double temperature,
    Structured structured,
    Agent agent
) {
    public AiProperties {
        if (temperature == null) {
            temperature = 0.2;
        }
        if (structured == null) {
            structured = new Structured(2, true, true, true, 200, false);
        }
        if (agent == null) {
            agent = new Agent(8);
        }
    }

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
