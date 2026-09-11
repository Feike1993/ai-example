package com.feike.ai.production.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 工业级链路配置，绑定 {@code app.production.*}。
 * <p>
 * 为什么单独一套前缀而不复用 {@code app.ai.rag}：教学样例的参数会被课程内容频繁调整
 * （对比不同 topK、不同分块策略），生产链路不能跟着抖动。两套前缀之后，
 * 改样例配置不会意外改变生产行为，语料也各写各的 corpus。
 * <p>
 * 可用性取舍：本模块依赖 Redis 存 run 状态与事件回放流，但 Redis 不可用时应用照常启动，
 * 只让 {@code /api/v1/**} 返回 503。与 {@code spring.datasource.hikari.initialization-fail-timeout: -1}
 * 是同一个思路——旁路依赖挂了不应该拖垮整个进程。
 *
 * @param enabled       是否装配工业级链路；属性缺失时默认 false，避免被现有部署无声引入
 * @param corpus        向量库语料名，与样例 corpus 隔离
 * @param topK          检索返回条数
 * @param chunkSize     分块目标长度（字符近似）
 * @param minSources    命中数低于此值视为空检索，直接拒答不调 LLM
 * @param hybridEnabled 是否启用向量 + 全文 + RRF 混合检索
 * @param rrfK           RRF 常数 k
 * @param keywordTopK    关键词路 topK
 * @param stream         SSE 流控参数
 * @param session        多轮会话参数
 * @param security       JWT / 信封加密
 * @param rateLimit      限流与幂等
 * @param agent          Agent 步数
 * @param users          演示账号
 * @param queryExpansion 查询扩展；默认 none，避免检索层额外打 LLM
 * @param media          本机图文 / 语音演示；不落盘、不进向量库
 */
@ConfigurationProperties(prefix = "app.production")
public record ProductionProperties(
    boolean enabled,
    String corpus,
    int topK,
    int chunkSize,
    int minSources,
    boolean hybridEnabled,
    int rrfK,
    int keywordTopK,
    Stream stream,
    Session session,
    Security security,
    RateLimit rateLimit,
    Agent agent,
    List<DemoUser> users,
    QueryExpansion queryExpansion,
    Media media
) {

    public ProductionProperties {
        if (corpus == null || corpus.isBlank()) {
            corpus = "ai-example-prod";
        }
        if (topK < 1) {
            topK = 4;
        }
        if (chunkSize < 50) {
            chunkSize = 400;
        }
        if (minSources < 1) {
            minSources = 1;
        }
        if (rrfK < 1) {
            rrfK = 60;
        }
        if (keywordTopK < 1) {
            keywordTopK = 4;
        }
        if (stream == null) {
            stream = new Stream(null, null, null, null);
        }
        if (session == null) {
            session = new Session(false, null, 0, 0, null, 0);
        }
        if (security == null) {
            security = new Security(null, null, null, null, null, null);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(0, null, 0, null);
        }
        if (agent == null) {
            agent = new Agent(0, null);
        }
        if (users == null || users.isEmpty()) {
            users = List.of(
                new DemoUser("alice", "demo", "tenant-a", List.of("USER")),
                new DemoUser("bob", "demo", "tenant-b", List.of("USER")),
                new DemoUser("admin", "demo", "tenant-a", List.of("ADMIN", "USER"))
            );
        }
        if (queryExpansion == null) {
            queryExpansion = new QueryExpansion(null, null);
        }
        if (media == null) {
            media = new Media(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * 查询扩展。默认 none：检索层不额外打 LLM，避免首字延迟和费用翻倍。
     * 打开 rewrite / hyde 是可灰度策略，请求字段可覆盖配置。
     *
     * @param defaultMode          none / rewrite / hyde
     * @param hydeFuseWithOriginal HyDE 向量路是否与原问题向量路 RRF 融合；缺省 true
     */
    public record QueryExpansion(String defaultMode, Boolean hydeFuseWithOriginal) {
        public QueryExpansion {
            if (defaultMode == null || defaultMode.isBlank()) {
                defaultMode = "none";
            } else {
                defaultMode = defaultMode.trim().toLowerCase().replace('-', '_');
                if (!"none".equals(defaultMode) && !"rewrite".equals(defaultMode) && !"hyde".equals(defaultMode)) {
                    defaultMode = "none";
                }
            }
            if (hydeFuseWithOriginal == null) {
                hydeFuseWithOriginal = Boolean.TRUE;
            }
        }

        /**
         * @return 是否把 HyDE 命中与原问题向量路融合
         */
        public boolean fuseHydeWithOriginal() {
            return Boolean.TRUE.equals(hydeFuseWithOriginal);
        }
    }

    /**
     * SSE 流控与事件回放参数。
     *
     * @param eventLog          {@code redis}（默认，跨实例可续传）或 {@code memory}（单进程，测试用）
     * @param replayTtl         run 事件保留窗口；超出后重连拿不到历史，只能重发
     * @param heartbeatInterval 心跳注释帧间隔，避免代理在模型长思考期间判定连接空闲
     * @param timeout           单条 run 的墙钟上限，超时以 error 事件收尾
     */
    public record Stream(
        String eventLog,
        Duration replayTtl,
        Duration heartbeatInterval,
        Duration timeout
    ) {
        public Stream {
            if (eventLog == null || eventLog.isBlank()) {
                eventLog = "redis";
            } else {
                eventLog = eventLog.trim().toLowerCase();
                if (!eventLog.equals("redis") && !eventLog.equals("memory")) {
                    eventLog = "redis";
                }
            }
            if (replayTtl == null || replayTtl.isNegative() || replayTtl.isZero()) {
                replayTtl = Duration.ofMinutes(10);
            }
            if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
                heartbeatInterval = Duration.ofSeconds(15);
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                timeout = Duration.ofMinutes(5);
            }
        }
    }

    /**
     * 多轮会话参数。
     * <p>
     * 单独一个开关而不是跟着 {@code app.production.enabled} 走：会话持久化要求
     * Flyway 迁移已经跑过，而工业级链路的其余部分（无状态问答、SSE 续传）不需要。
     * 分开之后，schema 还没就位的环境仍然能先把链路跑起来。
     *
     * @param enabled     是否启用多轮会话；关闭时问答退化为单轮，不读也不写会话表
     * @param lock        {@code redis}（默认，跨实例互斥）或 {@code memory}（单进程，测试用）
     * @param maxMessages 送入模型的历史条数上限
     * @param tokenBudget 历史的近似 token 上限；与 maxMessages 取更严的那个
     * @param lockTtl     会话锁存活上限，兜底持有者崩溃；须明显长于一次对话的墙钟上限
     * @param seqRetries  seq 取号冲突后的最大尝试次数
     */
    public record Session(
        boolean enabled,
        String lock,
        int maxMessages,
        int tokenBudget,
        Duration lockTtl,
        int seqRetries
    ) {
        public Session {
            if (lock == null || lock.isBlank()) {
                lock = "redis";
            } else {
                lock = lock.trim().toLowerCase();
                if (!lock.equals("redis") && !lock.equals("memory")) {
                    lock = "redis";
                }
            }
            if (maxMessages < 2) {
                maxMessages = 20;
            }
            if (tokenBudget < 100) {
                tokenBudget = 2000;
            }
            if (lockTtl == null || lockTtl.isNegative() || lockTtl.isZero()) {
                lockTtl = Duration.ofMinutes(6);
            }
            if (seqRetries < 1) {
                seqRetries = 3;
            }
        }
    }

    /**
     * JWT 与信封加密。
     *
     * @param kek          Base64 的 32 字节 AES KEK；空则工业级接口 503
     * @param secretStore  {@code postgres}（默认）或 {@code env}
     * @param kekId        写入密文行的版本号
     * @param jwtTtl       访问令牌有效期
     * @param denyWords    Prompt Injection 输入/输出词表
     * @param kekPrevious  轮换窗口内的上一把 KEK；空则只认当前钥匙
     */
    public record Security(
        String kek,
        String secretStore,
        String kekId,
        Duration jwtTtl,
        List<String> denyWords,
        String kekPrevious
    ) {
        public Security {
            if (secretStore == null || secretStore.isBlank()) {
                secretStore = "postgres";
            } else {
                secretStore = secretStore.trim().toLowerCase();
                if (!secretStore.equals("postgres") && !secretStore.equals("env")) {
                    secretStore = "postgres";
                }
            }
            if (kekId == null || kekId.isBlank()) {
                kekId = "v1";
            }
            if (jwtTtl == null || jwtTtl.isNegative() || jwtTtl.isZero()) {
                jwtTtl = Duration.ofHours(8);
            }
            if (denyWords == null || denyWords.isEmpty()) {
                denyWords = List.of("违禁演示词", "BLOCKED_DEMO");
            }
            if (kekPrevious != null && kekPrevious.isBlank()) {
                kekPrevious = null;
            }
        }
    }

    /**
     * 限流与 HTTP 幂等键。
     *
     * @param capacity        每个租户+主体在窗口内的请求上限
     * @param window          窗口长度
     * @param loginCapacity   登录接口按 IP 的更严上限
     * @param idempotencyTtl  Idempotency-Key 保留时间
     */
    public record RateLimit(
        int capacity,
        Duration window,
        int loginCapacity,
        Duration idempotencyTtl
    ) {
        public RateLimit {
            if (capacity < 1) {
                capacity = 30;
            }
            if (window == null || window.isNegative() || window.isZero()) {
                window = Duration.ofSeconds(60);
            }
            if (loginCapacity < 1) {
                loginCapacity = 10;
            }
            if (idempotencyTtl == null || idempotencyTtl.isNegative() || idempotencyTtl.isZero()) {
                idempotencyTtl = Duration.ofMinutes(10);
            }
        }
    }

    /**
     * 生产 Agent 参数。
     *
     * @param maxSteps 工具循环上限
     * @param publicTenantId 捆绑语料写入的租户标记，所有租户可读
     */
    public record Agent(int maxSteps, String publicTenantId) {
        public Agent {
            if (maxSteps < 1) {
                maxSteps = 6;
            }
            if (publicTenantId == null || publicTenantId.isBlank()) {
                publicTenantId = "public";
            }
        }
    }

    /**
     * 本机图文识图与语音演示。
     * <p>
     * 为什么单独一块而不是打开 Spring AI 的 OpenAI Audio 自动配置：那会和教学
     * {@code ChatModel} 抢同一套客户端；ASR/TTS 用手写 HTTP 调 compatible-mode，
     * 识图则覆盖现有 {@code OpenAiChatModel} 的模型名为视觉模型。文件不落盘、
     * 不进 pgvector，刷新后历史只剩 {@code [图片]} / {@code [文档]} 占位。
     *
     * @param maxBytes         单文件上限；默认 2MiB
     * @param imageMimes       识图允许的 mime
     * @param audioMimes       转写允许的 mime
     * @param visionModel      视觉模型名，默认 {@code qwen-vl-plus}
     * @param visionProvider   有图时默认 Provider，默认 dashscope
     * @param asrModel         ASR 模型
     * @param ttsModel         TTS 模型
     * @param ttsVoice         演示音色，写死一个配置项
     * @param maxSpeakChars    TTS 输入字符上限
     * @param maxImages        一轮问答最多几张图；默认 3
     * @param documentMimes    本轮文档允许的 mime
     * @param maxDocuments     一轮最多几份文档；默认 2
     * @param maxExtractChars  抽出正文合计上限
     * @param ocrMaxPages      扫描 PDF 最多渲染几页去做 VL 转写
     * @param ocrMinChars      低于此字数且有页则视为扫描候选
     */
    public record Media(
        Long maxBytes,
        List<String> imageMimes,
        List<String> audioMimes,
        String visionModel,
        String visionProvider,
        String asrModel,
        String ttsModel,
        String ttsVoice,
        Integer maxSpeakChars,
        Integer maxImages,
        List<String> documentMimes,
        Integer maxDocuments,
        Integer maxExtractChars,
        Integer ocrMaxPages,
        Integer ocrMinChars
    ) {
        /** 默认 2MiB，与探针 / 带图流式同一把尺子。 */
        public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024;

        public Media {
            if (maxBytes == null || maxBytes < 1) {
                maxBytes = DEFAULT_MAX_BYTES;
            }
            if (imageMimes == null || imageMimes.isEmpty()) {
                imageMimes = List.of("image/jpeg", "image/png", "image/webp");
            } else {
                imageMimes = imageMimes.stream().map(Media::normalizeMime).filter(s -> !s.isBlank()).toList();
            }
            if (audioMimes == null || audioMimes.isEmpty()) {
                audioMimes = List.of("audio/webm", "audio/wav", "audio/mpeg");
            } else {
                audioMimes = audioMimes.stream().map(Media::normalizeMime).filter(s -> !s.isBlank()).toList();
            }
            if (visionModel == null || visionModel.isBlank()) {
                visionModel = "qwen-vl-plus";
            }
            if (visionProvider == null || visionProvider.isBlank()) {
                visionProvider = "dashscope";
            }
            if (asrModel == null || asrModel.isBlank()) {
                asrModel = "qwen3-asr-flash";
            }
            if (ttsModel == null || ttsModel.isBlank()) {
                ttsModel = "qwen3-tts-flash";
            }
            if (ttsVoice == null || ttsVoice.isBlank()) {
                ttsVoice = "Cherry";
            }
            if (maxSpeakChars == null || maxSpeakChars < 1) {
                maxSpeakChars = 200;
            }
            if (maxImages == null || maxImages < 1) {
                maxImages = 3;
            }
            if (documentMimes == null || documentMimes.isEmpty()) {
                documentMimes = List.of(
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                );
            } else {
                documentMimes = documentMimes.stream().map(Media::normalizeMime).filter(s -> !s.isBlank()).toList();
            }
            if (maxDocuments == null || maxDocuments < 1) {
                maxDocuments = 2;
            }
            if (maxExtractChars == null || maxExtractChars < 1) {
                maxExtractChars = 8000;
            }
            if (ocrMaxPages == null || ocrMaxPages < 1) {
                ocrMaxPages = 3;
            }
            if (ocrMinChars == null || ocrMinChars < 1) {
                ocrMinChars = 20;
            }
        }

        /**
         * 去掉 charset 等参数，便于和允许列表比对。
         *
         * @param raw Content-Type
         * @return 小写主类型；空则空串
         */
        public static String normalizeMime(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String trimmed = raw.trim().toLowerCase();
            int semicolon = trimmed.indexOf(';');
            if (semicolon >= 0) {
                trimmed = trimmed.substring(0, semicolon).trim();
            }
            if ("image/jpg".equals(trimmed)) {
                return "image/jpeg";
            }
            if ("audio/wave".equals(trimmed) || "audio/x-wav".equals(trimmed)) {
                return "audio/wav";
            }
            if ("audio/mp3".equals(trimmed)) {
                return "audio/mpeg";
            }
            if ("text/x-markdown".equals(trimmed) || "text/x-md".equals(trimmed)) {
                return "text/markdown";
            }
            if ("application/x-pdf".equals(trimmed)) {
                return "application/pdf";
            }
            return trimmed;
        }
    }

    /**
     * 演示账号。密码可以是明文（启动时 BCrypt）或已是 {@code $2} 哈希。
     *
     * @param username 登录名
     * @param password 密码或 BCrypt
     * @param tenant   租户
     * @param roles    角色，含 {@code USER} / {@code ADMIN}
     */
    public record DemoUser(String username, String password, String tenant, List<String> roles) {
        public DemoUser {
            if (roles == null || roles.isEmpty()) {
                roles = List.of("USER");
            }
        }
    }
}
