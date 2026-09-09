package com.feike.ai.production.lock.manager;

import com.feike.ai.production.sse.dao.impl.RedisRunEventLogDAOImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 实现：{@code SET key token NX PX ttl} 加锁，Lua 脚本比对 token 后删除来释放。
 * <p>
 * 释放必须走 CAS 而不是直接 DEL：持有者若因为 GC 或慢请求超过了 TTL，锁已经过期并被
 * 另一个请求拿走，此时裸 DEL 删掉的是别人的锁，两个请求会同时进入临界区——
 * 而且第一个还毫不知情。比对 token 至少能让越界释放变成一次无操作。
 * <p>
 * TTL 是必需的兜底：持有者进程被 kill 时没有机会释放，只有过期能让会话恢复可用。
 * 因此 TTL 要明显长于一次对话的墙钟上限，取 {@code app.production.stream.timeout} 的量级。
 * <p>
 * 失败取向与 {@code RedisRunEventLogDAOImpl} 相反：那里 Redis 挂了要报 503，因为断线续传是对外承诺；
 * 这里 Redis 挂了<b>放行</b>。锁只是优化，为了它把整条问答链路停掉不划算，
 * 数据一致性本来就由数据库约束保证。
 */
public class RedisSessionLock implements SessionLock {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionLock.class);

    /** 比对 token 再删；相等才删，避免释放他人持有的同名锁。 */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
        """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """,
        Long.class
    );

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /**
     * @param redis 字符串模板
     * @param ttl   锁的存活上限，兜底持有者崩溃
     */
    public RedisSessionLock(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<Handle> tryAcquire(String sessionId) {
        String key = key(sessionId);
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        } catch (RuntimeException ex) {
            // Redis 不可用时放行：见类注释，锁是优化不是正确性前提
            log.warn("session={} 获取锁失败，降级为不加锁: {}", sessionId, ex.toString());
            return Optional.of(() -> { });
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new RedisHandle(key, token));
    }

    private static String key(String sessionId) {
        return "prod:session:" + sessionId + ":lock";
    }

    private final class RedisHandle implements Handle {

        private final String key;
        private final String token;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private RedisHandle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (RuntimeException ex) {
                // 释放失败不上抛：调用方通常在 finally 里 close，抛出去会盖掉真正的业务异常。
                // 锁最迟会在 TTL 到期后自己消失。
                log.warn("释放锁 {} 失败，等待 TTL 过期: {}", key, ex.toString());
            }
        }
    }
}
