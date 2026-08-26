package com.feike.ai.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 接入与样例行为配置，绑定 {@code app.ai.*}。
 * <p>
 * 多 Provider：按 id 选 OpenAI 兼容网关，默认 DeepSeek。Embedding 与 Chat 分离。
 *
 * @param defaultProvider     未传 provider 时使用的 id
 * @param temperature         全局默认采样温度；Provider 未单独配置时用此值
 * @param providers           OpenAI 兼容网关列表，key 为 provider id
 * @param structured          结构化输出重试策略
 * @param agent               Agent Loop 步数上限
 * @param embeddingProvider   Embedding 用的 Provider id（本仓固定 dashscope）
 * @param embedding           Embedding 模型与维度
 * @param rag                 RAG 分块 / topK / 开关
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
    String defaultProvider,
    Double temperature,
    Map<String, Provider> providers,
    Structured structured,
    Agent agent,
    String embeddingProvider,
    Embedding embedding,
    Rag rag
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
        if (embeddingProvider == null || embeddingProvider.isBlank()) {
            embeddingProvider = "dashscope";
        }
        if (embedding == null) {
            embedding = new Embedding("text-embedding-v3", 1024);
        }
        if (rag == null) {
            rag = new Rag(true, 4, 400);
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

    /**
     * Embedding 模型配置（与 Chat Provider 的 model 字段独立）。
     *
     * @param model      如 text-embedding-v3
     * @param dimensions 向量维度，须与 pgvector 表一致
     */
    public record Embedding(String model, int dimensions) {
        public Embedding {
            if (model == null || model.isBlank()) {
                model = "text-embedding-v3";
            }
            if (dimensions < 1) {
                dimensions = 1024;
            }
        }
    }

    /**
     * RAG 样例开关与检索参数。
     *
     * @param enabled   为 false 时不注册 Embedding / VectorStore 相关 Bean（仍可跑 MCP）
     * @param topK      在线检索返回条数
     * @param chunkSize Token 分块目标大小
     */
    public record Rag(boolean enabled, int topK, int chunkSize) {
        public Rag {
            if (topK < 1) {
                topK = 1;
            }
            if (chunkSize < 50) {
                chunkSize = 50;
            }
        }
    }
}
