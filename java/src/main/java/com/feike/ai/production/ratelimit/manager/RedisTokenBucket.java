package com.feike.ai.production.ratelimit.manager;

import com.feike.ai.production.ratelimit.service.RateLimitExceededException;

import com.feike.ai.production.config.ProductionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Redis 令牌桶。脚本在 Redis 内原子完成「补令牌 + 扣 1」。
 * <p>
 * Redis 挂了时放行并打 warn：限流是保护性优化，为它把整条问答停掉不划算。
 * 这与会话锁同一取向，与 SSE 事件日志的 fail-closed 相反。
 */
public class RedisTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    /**
     * KEYS[1] key
     * ARGV[1] capacity
     * ARGV[2] refill_per_ms
     * ARGV[3] now_ms
     * ARGV[4] ttl_ms
     * 返回 {allowed, remaining, retry_ms}
     */
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(
        """
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            local data = redis.call('hmget', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local elapsed = now - ts
            if elapsed < 0 then
              elapsed = 0
            end
            tokens = math.min(capacity, tokens + elapsed * refill)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              if refill > 0 then
                retry = math.ceil((1 - tokens) / refill)
              else
                retry = ttl
              end
            end
            redis.call('hset', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('pexpire', KEYS[1], ttl)
            return {allowed, math.floor(tokens), retry}
            """,
        List.class
    );

    private final StringRedisTemplate redis;
    private final int capacity;
    private final Duration window;

    /**
     * @param redis      Redis
     * @param properties 容量与窗口
     */
    public RedisTokenBucket(StringRedisTemplate redis, ProductionProperties properties) {
        this.redis = redis;
        this.capacity = properties.rateLimit().capacity();
        this.window = properties.rateLimit().window();
    }

    /**
     * 尝试扣一个令牌。
     *
     * @param key      Redis key
     * @param capacity 覆盖默认容量；{@code null} 用配置值
     * @return 剩余令牌（失败时仍返回当前估计）
     */
    public int consume(String key, Integer capacity) {
        int cap = capacity == null || capacity < 1 ? this.capacity : capacity;
        double refillPerMs = cap / (double) window.toMillis();
        long now = System.currentTimeMillis();
        long ttl = window.toMillis() * 2;
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) (List<?>) redis.execute(
                SCRIPT,
                List.of(key),
                String.valueOf(cap),
                String.valueOf(refillPerMs),
                String.valueOf(now),
                String.valueOf(ttl)
            );
            if (result == null || result.size() < 3) {
                return cap;
            }
            long allowed = toLong(result.get(0));
            int remaining = (int) toLong(result.get(1));
            long retryMs = toLong(result.get(2));
            if (allowed != 1) {
                throw new RateLimitExceededException(Math.max(1, retryMs / 1000), remaining);
            }
            return remaining;
        } catch (RateLimitExceededException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("限流 Redis 不可用，放行: {}", ex.toString());
            return cap;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
