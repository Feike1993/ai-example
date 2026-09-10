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
 * 设计思路：每个限流维度（如租户+用户或登录 IP）对应一个 Hash，只保存当前令牌数
 * {@code tokens} 与上次结算时间 {@code ts}，不按请求逐条存储。请求到达时按
 * {@code tokens = min(capacity, tokens + elapsed * refillPerMs)} 补回令牌；余额不少于 1
 * 则扣减并放行，否则计算补足一个令牌还需等待的时间。
 * <p>
 * 整个「读状态 → 补充 → 判断 → 写回 → 设置过期」流程在 Lua 脚本中一次执行。Redis 对单个
 * 脚本串行执行，避免多个应用实例同时读到同一余额后重复放行；若拆为多次客户端命令则会产生
 * 竞争窗口。TTL 设为两个窗口，闲置桶自动回收，同时保留一个窗口内的状态以保证补充计算连续。
 * <p>
 * Redis 挂了时放行并打 warn：限流是保护性优化，为它把整条问答停掉不划算。
 * 这与会话锁同一取向，与 SSE 事件日志的 fail-closed 相反。
 */
public class RedisTokenBucket {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucket.class);

    /**
     * 单次 Lua 调用的输入与输出约定。
     * <p>
     * 先从 Hash 读取 {@code tokens/ts}；新桶以满容量开始。时钟回拨时 elapsed 按 0 处理，
     * 防止错误地扣掉已有令牌。补充后再决定是否扣减，最后无论放行与否都写回状态和 TTL，
     * 使被拒请求也能推进时间戳并得到准确的 retry 时间。
     *
     * <ul>
     *   <li>{@code KEYS[1]}：桶的 Redis key</li>
     *   <li>{@code ARGV[1..4]}：容量、每毫秒补充量、当前毫秒、过期毫秒</li>
     *   <li>返回：是否放行、扣减后的整数余额、预计还需等待的毫秒</li>
     * </ul>
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
     * <p>
     * 步骤：确定容量与补充速率；将当前时间交给 Lua 结算余额；脚本返回拒绝时转成
     * {@link RateLimitExceededException}，由 Web 层产生 429 与 {@code Retry-After}。
     * Redis 通信或脚本结果异常时降级放行，返回满容量作为未知余额的保守展示值。
     *
     * @param key      Redis key
     * @param capacity 覆盖默认容量；{@code null} 用配置值
     * @return 剩余令牌（失败时仍返回当前估计）
     */
    public int consume(String key, Integer capacity) {
        int cap = capacity == null || capacity < 1 ? this.capacity : capacity;
        // 每毫秒补充量
        double refillPerMs = cap / (double) window.toMillis();
        // 当前时间
        long now = System.currentTimeMillis();
        // 过期时间
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
