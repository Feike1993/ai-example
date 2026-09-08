package com.feike.ai.production.lock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 会话锁：互斥、TTL 兜底、CAS 释放不误删。
 * <p>
 * 跑法：{@code RUN_REDIS_IT=true ./gradlew test --tests RedisSessionLockIT}
 * <p>
 * 与 {@code RedisRunEventLogIT} 一样不起 Spring 上下文：要验证的是这个类和 Redis 的交互，
 * 不该被应用装配的可用性绑架。
 * <p>
 * 设了 {@code REDIS_IT_HOST} 就直接连那个实例，不再起容器——CI 上常以 service 形式
 * 提供 Redis，本机 Testcontainers 与某些 Docker 版本不兼容时也只能走这条路。
 */
@DisplayName("RedisSessionLockIT")
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class RedisSessionLockIT {

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
    void secondAcquireShouldBeRejectedWhileHeld() {
        SessionLock lock = newLock(Duration.ofMinutes(1));
        String sessionId = newSessionId();

        try (SessionLock.Handle ignored = lock.tryAcquire(sessionId).orElseThrow()) {
            assertTrue(lock.tryAcquire(sessionId).isEmpty(), "持有期间不应再次拿到");
            // 不同会话之间互不影响，否则一个热点会话会把整个实例串行化
            assertTrue(lock.tryAcquire(newSessionId()).isPresent());
        }

        assertTrue(lock.tryAcquire(sessionId).isPresent(), "释放后应可重新获取");
    }

    @Test
    void closeShouldBeIdempotent() {
        SessionLock lock = newLock(Duration.ofMinutes(1));
        String sessionId = newSessionId();

        SessionLock.Handle handle = lock.tryAcquire(sessionId).orElseThrow();
        handle.close();
        SessionLock.Handle second = lock.tryAcquire(sessionId).orElseThrow();
        // 第一个句柄重复 close 不能把第二个持有者的锁删掉
        handle.close();

        assertTrue(lock.tryAcquire(sessionId).isEmpty(), "重复 close 不应释放他人的锁");
        second.close();
    }

    @Test
    void expiredLockShouldBeReclaimable() throws Exception {
        SessionLock lock = newLock(Duration.ofMillis(300));
        String sessionId = newSessionId();

        SessionLock.Handle stale = lock.tryAcquire(sessionId).orElseThrow();
        assertTrue(lock.tryAcquire(sessionId).isEmpty());

        // 持有者进程被 kill 时没机会释放，只有 TTL 能让会话恢复可用
        Thread.sleep(600L);
        Optional<SessionLock.Handle> reclaimed = lock.tryAcquire(sessionId);
        assertTrue(reclaimed.isPresent(), "TTL 过期后应可被接管");

        // 这里是 CAS 释放存在的全部理由：原持有者此刻才回过神来释放，
        // 裸 DEL 会删掉接管者的锁，于是两个请求同时进入临界区且都不知情
        stale.close();
        assertTrue(lock.tryAcquire(sessionId).isEmpty(), "超时持有者的释放不得误删接管者的锁");

        reclaimed.get().close();
        assertFalse(lock.tryAcquire(sessionId).isEmpty());
    }

    private static SessionLock newLock(Duration ttl) {
        return new RedisSessionLock(redis, ttl);
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
