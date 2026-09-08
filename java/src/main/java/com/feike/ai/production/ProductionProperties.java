package com.feike.ai.production;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
 * @param rrfK          RRF 常数 k
 * @param keywordTopK   关键词路 topK
 * @param stream        SSE 流控参数
 * @param session       多轮会话参数
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
    Session session
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
}
