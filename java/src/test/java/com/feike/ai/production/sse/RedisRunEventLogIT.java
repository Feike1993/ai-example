package com.feike.ai.production.sse;

import com.feike.ai.production.ProductionProperties;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 事件回放集成测：需要本机 Docker，且设置 {@code RUN_REDIS_IT=true}。
 * <p>
 * 跑法：{@code RUN_REDIS_IT=true ./gradlew test --tests RedisRunEventLogIT}
 * <p>
 * 只起容器不起 Spring 上下文：这里验证的是 XADD / XRANGE 的语义，
 * 拉起整个应用只会让失败原因变模糊。
 */
@DisplayName("RedisRunEventLogIT")
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_IT", matches = "true")
class RedisRunEventLogIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisRunEventLog eventLog;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        eventLog = new RedisRunEventLog(redis, properties(Duration.ofMinutes(5)));
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }

    @Test
    void shouldReplayOnlyEventsAfterGivenSeq() {
        String runId = newRunId();
        eventLog.begin(runId);
        eventLog.append(runId, new StreamEvent(0, StreamEventType.meta, "{\"runId\":\"x\"}"));
        eventLog.append(runId, new StreamEvent(1, StreamEventType.sources, "{\"sources\":[]}"));
        eventLog.append(runId, new StreamEvent(2, StreamEventType.delta, "\"答\""));
        eventLog.append(runId, new StreamEvent(3, StreamEventType.done, "{}"));
        eventLog.finish(runId, RunState.DONE);

        List<StreamEvent> tail = eventLog.replay(runId, 1);

        assertEquals(List.of(2L, 3L), tail.stream().map(StreamEvent::seq).toList());
        assertEquals(StreamEventType.delta, tail.getFirst().type());
        assertEquals("\"答\"", tail.getFirst().data());
    }

    @Test
    void shouldReplayEverythingWhenNoLastEventId() {
        String runId = newRunId();
        eventLog.begin(runId);
        eventLog.append(runId, new StreamEvent(0, StreamEventType.meta, "{}"));
        eventLog.append(runId, new StreamEvent(1, StreamEventType.done, "{}"));

        assertEquals(2, eventLog.replay(runId, -1).size());
    }

    @Test
    void snapshotShouldTrackStateAndLastSeq() {
        String runId = newRunId();
        eventLog.begin(runId);
        assertEquals(RunState.PENDING, eventLog.snapshot(runId).orElseThrow().state());

        eventLog.append(runId, new StreamEvent(0, StreamEventType.delta, "\"a\""));
        RunSnapshot streaming = eventLog.snapshot(runId).orElseThrow();
        assertEquals(RunState.STREAMING, streaming.state());
        assertEquals(0, streaming.lastSeq());

        eventLog.finish(runId, RunState.ERROR);
        assertEquals(RunState.ERROR, eventLog.snapshot(runId).orElseThrow().state());
    }

    @Test
    void terminalStateShouldNotBeOverwrittenByLateAppend() {
        String runId = newRunId();
        eventLog.begin(runId);
        eventLog.finish(runId, RunState.CANCELLED);
        eventLog.append(runId, new StreamEvent(0, StreamEventType.delta, "\"迟到\""));

        assertEquals(RunState.CANCELLED, eventLog.snapshot(runId).orElseThrow().state());
    }

    @Test
    void expiredRunShouldLookMissingSoCallerCanReturnGone() throws InterruptedException {
        RedisRunEventLog shortLived = new RedisRunEventLog(redis, properties(Duration.ofSeconds(1)));
        String runId = newRunId();
        shortLived.begin(runId);
        shortLived.append(runId, new StreamEvent(0, StreamEventType.done, "{}"));

        assertTrue(shortLived.snapshot(runId).isPresent());
        Thread.sleep(1_500L);
        assertTrue(shortLived.snapshot(runId).isEmpty(), "超过保留窗口后应查不到，由调用方转 410");
    }

    private static String newRunId() {
        return UUID.randomUUID().toString();
    }

    private static ProductionProperties properties(Duration replayTtl) {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream("redis", replayTtl, Duration.ofSeconds(15), Duration.ofMinutes(5))
        );
    }
}
