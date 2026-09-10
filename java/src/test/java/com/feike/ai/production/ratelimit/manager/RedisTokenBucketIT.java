package com.feike.ai.production.ratelimit.manager;

import com.feike.ai.production.ratelimit.service.RateLimitExceededException;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶与幂等键的 Redis 行为。
 * <p>
 * 跑法：{@code RUN_REDIS_IT=true ./gradlew test --tests RedisTokenBucketIT}
 */
@DisplayName("RedisTokenBucketIT")
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class RedisTokenBucketIT {

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        String host = System.getenv("REDIS_IT_HOST");
        int port;
        if (host == null || host.isBlank()) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        } else {
            String rawPort = System.getenv("REDIS_IT_PORT");
            port = rawPort == null || rawPort.isBlank() ? 6379 : Integer.parseInt(rawPort);
        }
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void exceedingCapacityShouldThrow429() {
        ProductionProperties properties = properties(2, Duration.ofSeconds(30));
        RedisTokenBucket bucket = new RedisTokenBucket(redis, properties);
        String key = "prod:rl:it:" + UUID.randomUUID();
        bucket.consume(key, 2);
        bucket.consume(key, 2);
        RateLimitExceededException ex = assertThrows(
            RateLimitExceededException.class,
            () -> bucket.consume(key, 2)
        );
        assertTrue(ex.retryAfterSeconds() >= 1);
    }

    @Test
    void sameIdempotencyKeyDifferentBodyShouldConflict() {
        ProductionProperties properties = properties(30, Duration.ofSeconds(60));
        IdempotencyManager store = new IdempotencyManager(redis, properties, JsonMapper.builder().build());
        String key = UUID.randomUUID().toString();
        Optional<String> first = store.begin("tenant-a", key, "hash-a");
        assertTrue(first.isEmpty());
        store.complete("tenant-a", key, "hash-a", java.util.Map.of("ok", true));
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> store.begin("tenant-a", key, "hash-b")
        );
        assertEquals(ErrorCodeEnum.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
    }

    @Test
    void sameIdempotencyKeySameBodyShouldReplay() {
        ProductionProperties properties = properties(30, Duration.ofSeconds(60));
        IdempotencyManager store = new IdempotencyManager(redis, properties, JsonMapper.builder().build());
        String key = UUID.randomUUID().toString();
        store.begin("tenant-a", key, "hash-a");
        store.complete("tenant-a", key, "hash-a", java.util.Map.of("answer", "yes"));
        Optional<String> replayed = store.begin("tenant-a", key, "hash-a");
        assertTrue(replayed.isPresent());
        assertTrue(replayed.get().contains("yes"));
    }

    private static ProductionProperties properties(int capacity, Duration window) {
        return new ProductionProperties(
            true, "c", 4, 400, 1, true, 60, 4, null, null, null,
            new ProductionProperties.RateLimit(capacity, window, 10, Duration.ofMinutes(10)),
            null, null, null
        );
    }
}
