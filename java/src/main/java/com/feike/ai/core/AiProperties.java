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
 * @param context             上下文工程：消息条数、token 预算、存储实现
 * @param multiagent          多 Agent 步数上限
 * @param memory              长期记忆召回参数
 * @param mcp                 MCP 样例：remote / inprocess（配置仅为启动初始值，运行时可由 PUT /mcp/mode 覆盖）
 * @param guardrail           输出护栏：敏感词表等
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
    Rag rag,
    ContextSettings context,
    MultiAgent multiagent,
    Memory memory,
    Mcp mcp,
    Guardrail guardrail
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
            rag = new Rag(true, 4, 400, 1, true, new Rag.Hybrid(true, 60, 4, false), new Rag.Hyde(true, true), new Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true));
        }
        if (context == null) {
            context = new ContextSettings(24, 2000, 6, "jdbc");
        }
        if (multiagent == null) {
            multiagent = new MultiAgent(4, 6);
        }
        if (memory == null) {
            memory = new Memory(4, "demo", 0.92, 5);
        }
        if (mcp == null) {
            mcp = new Mcp("remote", "dev-mcp-token");
        }
        if (guardrail == null) {
            guardrail = new Guardrail(java.util.List.of("违禁演示词", "BLOCKED_DEMO"));
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
     * @param enabled           为 false 时不注册 Embedding / VectorStore 相关 Bean（仍可跑 MCP）
     * @param topK              在线检索返回条数
     * @param chunkSize         Token 分块目标大小
     * @param minSources        命中条数低于此值视为空检索（默认 1，即 hits 为空）
     * @param skipLlmWhenEmpty  空检索时是否跳过 LLM、直接返回固定拒答（默认 true）
     * @param hybrid            Hybrid 检索（向量 + PG 全文 + RRF）参数
     * @param hyde              HyDE 假想文档检索参数
     * @param chunking          分块策略 / 旁路 corpus（第七期）
     */
    public record Rag(
        boolean enabled,
        int topK,
        int chunkSize,
        int minSources,
        boolean skipLlmWhenEmpty,
        Hybrid hybrid,
        Hyde hyde,
        Chunking chunking
    ) {
        public Rag {
            if (topK < 1) {
                topK = 1;
            }
            if (chunkSize < 50) {
                chunkSize = 50;
            }
            if (minSources < 1) {
                minSources = 1;
            }
            if (hybrid == null) {
                hybrid = new Hybrid(true, 60, 4, false);
            }
            if (hyde == null) {
                hyde = new Hyde(true, true);
            }
            if (chunking == null) {
                chunking = new Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true);
            }
        }

        /**
         * Hybrid RAG：RRF 融合与可选查询改写。
         *
         * @param enabled              为 false 时 {@code retrievalMode=hybrid} 回退纯向量
         * @param rrfK                 RRF 常数 k
         * @param keywordTopK          关键词路 topK
         * @param rewriteQueryEnabled  是否用一次 Chat 把口语问题改写成检索友好短句
         */
        public record Hybrid(boolean enabled, int rrfK, int keywordTopK, boolean rewriteQueryEnabled) {
            public Hybrid {
                if (rrfK < 1) {
                    rrfK = 60;
                }
                if (keywordTopK < 1) {
                    keywordTopK = 4;
                }
            }
        }

        /**
         * HyDE：假想文档 Embedding 检索。
         *
         * @param enabled           是否允许 queryExpansion=hyde
         * @param fuseWithOriginal  假想段落向量路是否与原问题向量路 RRF 融合
         */
        public record Hyde(boolean enabled, boolean fuseWithOriginal) {}

        /**
         * 分块旁路配置（7a 语义 corpus；7b 父子 corpus / 子块大小 / 展开）。
         *
         * @param semanticCorpus 语义分块写入的 corpus 名
         * @param parentCorpus   父子文档子块写入的 corpus 名
         * @param childSize      子块目标长度（字符近似）
         * @param expandParent   检索后是否用 parentText 拼上下文
         */
        public record Chunking(String semanticCorpus, String parentCorpus, int childSize, boolean expandParent) {
            public Chunking {
                if (semanticCorpus == null || semanticCorpus.isBlank()) {
                    semanticCorpus = "ai-example-demo-semantic";
                }
                if (parentCorpus == null || parentCorpus.isBlank()) {
                    parentCorpus = "ai-example-demo-parent";
                }
                if (childSize < 40) {
                    childSize = 200;
                }
            }
        }
    }

    /**
     * MCP 样例模式与远端凭证。
     *
     * @param mode               {@code remote}（默认，连旁进程 Server）或 {@code inprocess}（同进程工具）；
     *                           仅绑定配置初始值，运行时切换见 {@code McpSampleService#setMode}
     * @param remoteBearerToken  remote Client 请求携带的 Bearer 明文；与 mcp-server {@code MCP_BEARER_TOKEN} 一致
     */
    public record Mcp(String mode, String remoteBearerToken) {
        public Mcp {
            if (mode == null || mode.isBlank()) {
                mode = "remote";
            } else {
                mode = mode.trim().toLowerCase();
                if (!mode.equals("remote") && !mode.equals("inprocess")) {
                    mode = "remote";
                }
            }
            if (remoteBearerToken == null || remoteBearerToken.isBlank()) {
                remoteBearerToken = "dev-mcp-token";
            } else {
                remoteBearerToken = remoteBearerToken.trim();
            }
        }

        public boolean remote() {
            return "remote".equals(mode);
        }
    }

    /**
     * 上下文工程预算与存储。
     *
     * @param maxMessages        送入模型的最大消息条数（含 system）
     * @param tokenBudget        近似 token 上限（字符数/4）
     * @param keepRecentMessages summarize 时保留的最近 user/assistant 条数
     * @param store              {@code jdbc}（默认）或 {@code memory}
     */
    public record ContextSettings(int maxMessages, int tokenBudget, int keepRecentMessages, String store) {
        public ContextSettings {
            if (maxMessages < 4) {
                maxMessages = 4;
            }
            if (tokenBudget < 100) {
                tokenBudget = 100;
            }
            if (keepRecentMessages < 2) {
                keepRecentMessages = 2;
            }
            if (store == null || store.isBlank()) {
                store = "jdbc";
            } else {
                store = store.trim().toLowerCase();
                if (!store.equals("jdbc") && !store.equals("memory")) {
                    store = "jdbc";
                }
            }
        }
    }

    /**
     * 长期记忆样例配置。
     *
     * @param topK                 召回条数
     * @param userIdDefault        未传 userId 时的默认用户
     * @param similarityThreshold  相似合并阈值（0–1，越高越严）；达到则删旧写新
     * @param extractMaxFacts      单次自动抽取最多写入条数（第八期）
     */
    public record Memory(int topK, String userIdDefault, double similarityThreshold, int extractMaxFacts) {
        public Memory {
            if (topK < 1) {
                topK = 4;
            }
            if (userIdDefault == null || userIdDefault.isBlank()) {
                userIdDefault = "demo";
            }
            if (similarityThreshold <= 0 || similarityThreshold > 1) {
                similarityThreshold = 0.92;
            }
            if (extractMaxFacts < 1) {
                extractMaxFacts = 5;
            }
        }
    }

    /**
     * 多 Agent 熔断。
     *
     * @param maxOrchestratorSteps Orchestrator 最大交接轮次
     * @param maxWorkerSteps       专员 ReAct 最大步数
     */
    public record MultiAgent(int maxOrchestratorSteps, int maxWorkerSteps) {
        public MultiAgent {
            if (maxOrchestratorSteps < 1) {
                maxOrchestratorSteps = 1;
            }
            if (maxWorkerSteps < 1) {
                maxWorkerSteps = 1;
            }
        }
    }

    /**
     * 输出护栏：确定性敏感词表（第十一期）。
     *
     * @param denyWords 命中即拒答的子串列表；大小写不敏感
     */
    public record Guardrail(java.util.List<String> denyWords) {
        public Guardrail {
            if (denyWords == null || denyWords.isEmpty()) {
                denyWords = java.util.List.of("违禁演示词", "BLOCKED_DEMO");
            } else {
                denyWords = denyWords.stream()
                    .filter(w -> w != null && !w.isBlank())
                    .map(String::trim)
                    .toList();
                if (denyWords.isEmpty()) {
                    denyWords = java.util.List.of("违禁演示词", "BLOCKED_DEMO");
                }
            }
        }
    }
}
