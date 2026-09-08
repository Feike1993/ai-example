package com.feike.ai.production.lock;

import java.util.Optional;

/**
 * 会话级互斥，保证同一 sessionId 同时只有一次对话在跑。
 *
 * <h2>它解决什么</h2>
 * 同一会话并发两轮时，两边读到的是同一份历史，后写入的那轮看不见前一轮的问答，
 * 上下文就断了。这类错乱数据库约束拦不住——两轮的 seq 都合法。
 *
 * <h2>它不解决什么</h2>
 * 锁只是<b>减少冲突</b>并给用户一个明确的「会话忙」提示。分布式锁在 Redis 主从切换、
 * 网络分区、持有者 GC 停顿超过 TTL 时都可能被两个持有者同时认为归自己所有。
 * 因此写入的正确性必须独立成立：真正兜底的是 {@code prod_chat_message} 的
 * 复合主键与 {@code turn_id} 唯一索引。任何「因为有锁所以可以省掉约束」的推论都是错的。
 */
public interface SessionLock {

    /**
     * 尝试获取锁，不等待。
     *
     * @param sessionId 会话 id
     * @return 拿到则返回句柄；被占用时返回空
     */
    Optional<Handle> tryAcquire(String sessionId);

    /**
     * 锁句柄。用 {@link AutoCloseable} 是为了逼调用方写在 try-with-resources 里——
     * 生成过程中抛异常时忘记释放，会让整个会话被锁到 TTL 过期。
     */
    interface Handle extends AutoCloseable {

        /**
         * 释放锁。重复调用无副作用；非持有者调用不会误删他人的锁。
         */
        @Override
        void close();
    }
}
