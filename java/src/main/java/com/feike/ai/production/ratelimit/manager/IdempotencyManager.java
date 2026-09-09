package com.feike.ai.production.ratelimit.manager;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * HTTP {@code Idempotency-Key}：同一租户同一 key 只接受同一份请求体。
 * <p>
 * Redis 挂了 fail-open。GET SSE 不走这里，断线续传已经有 runId。
 */
public class IdempotencyManager {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyManager.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final JsonMapper jsonMapper;

    /**
     * @param redis      Redis
     * @param properties TTL
     * @param jsonMapper 序列化缓存响应
     */
    public IdempotencyManager(StringRedisTemplate redis, ProductionProperties properties, JsonMapper jsonMapper) {
        this.redis = redis;
        this.ttl = properties.rateLimit().idempotencyTtl();
        this.jsonMapper = jsonMapper;
    }

    /**
     * 开始一次幂等请求。
     *
     * @param tenantId 租户
     * @param key      客户端 Idempotency-Key
     * @param bodyHash 请求体摘要
     * @return 已缓存的响应 JSON；空表示本次是首次，调用方应在结束后 {@link #complete}
     */
    public Optional<String> begin(String tenantId, String key, String bodyHash) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String redisKey = redisKey(tenantId, key);
        try {
            String existing = redis.opsForValue().get(redisKey);
            if (existing == null) {
                String pending = "pending:" + bodyHash;
                Boolean stored = redis.opsForValue().setIfAbsent(redisKey, pending, ttl);
                if (!Boolean.TRUE.equals(stored)) {
                    return begin(tenantId, key, bodyHash);
                }
                return Optional.empty();
            }
            if (existing.startsWith("pending:")) {
                String previousHash = existing.substring("pending:".length());
                if (!previousHash.equals(bodyHash)) {
                    throw conflict();
                }
                throw new BusinessException(ErrorCodeEnum.IDEMPOTENCY_CONFLICT, "相同幂等键的请求仍在处理");
            }
            if (existing.startsWith("done:")) {
                int split = existing.indexOf(':', 5);
                if (split < 0) {
                    throw conflict();
                }
                String previousHash = existing.substring(5, split);
                String payload = existing.substring(split + 1);
                if (!previousHash.equals(bodyHash)) {
                    throw conflict();
                }
                return Optional.of(payload);
            }
            throw conflict();
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("幂等键 Redis 不可用，放行: {}", ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 写入成功响应。
     *
     * @param tenantId 租户
     * @param key      幂等键
     * @param bodyHash 请求体摘要
     * @param payload  JSON 响应
     */
    public void complete(String tenantId, String key, String bodyHash, Object payload) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String json = jsonMapper.writeValueAsString(payload);
            redis.opsForValue().set(
                redisKey(tenantId, key),
                "done:" + bodyHash + ":" + json,
                ttl.toMillis(),
                TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException ex) {
            log.warn("写入幂等缓存失败: {}", ex.toString());
        }
    }

    /**
     * SHA-256 十六进制摘要。
     *
     * @param body 原始请求体
     * @return hex
     */
    public static String sha256(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String redisKey(String tenantId, String key) {
        return "prod:idem:" + tenantId + ":" + key.trim();
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCodeEnum.IDEMPOTENCY_CONFLICT);
    }
}
