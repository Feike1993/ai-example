package com.feike.ai.production.sse.service;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次 SSE 连接断开后，run 必须在后台继续到终态，供 Last-Event-ID 续传补齐。
 */
@DisplayName("SseRunExecutor disconnect")
class SseRunExecutorDisconnectTest {

    private final InMemoryRunEventLogDAOImpl eventLog = new InMemoryRunEventLogDAOImpl();
    private final CountDownLatch triggerDisconnect = new CountDownLatch(1);
    private final CountDownLatch pipelineFinished = new CountDownLatch(1);
    private final AtomicReference<String> runId = new AtomicReference<>();
    private final SseRunExecutor executor = new SseRunExecutor(
        eventLog, JsonMapper.builder().build(), properties()) {
        @Override
        EventSink createSink(SseEmitter emitter) {
            return new EventSink() {
                private final AtomicInteger writes = new AtomicInteger();

                @Override
                public void write(StreamEvent event) throws IOException {
                    if (writes.incrementAndGet() >= 2) {
                        throw new IOException("客户端已断开");
                    }
                }

                @Override
                public void heartbeat() {
                }

                @Override
                public void complete() {
                }
            };
        }
    };

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void disconnectShouldNotCancelBackgroundRun() throws Exception {
        executor.start(writer -> {
            try {
                writer.meta(Map.of("tenant", "tenant-a"));
                triggerDisconnect.await();
                writer.delta("触发断连");
                Thread.sleep(20L);
                writer.delta("断连后继续生成");
                writer.done(Map.of());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                pipelineFinished.countDown();
            }
        }, runId::set, "tenant-a");

        triggerDisconnect.countDown();
        assertTrue(pipelineFinished.await(2, TimeUnit.SECONDS), "后台管线应继续执行完成");

        assertEquals(RunStateEnum.DONE, eventLog.snapshot(runId.get()).orElseThrow().state());
        assertEquals(
            List.of(
                StreamEventTypeEnum.META,
                StreamEventTypeEnum.DELTA,
                StreamEventTypeEnum.DELTA,
                StreamEventTypeEnum.DONE
            ),
            eventLog.replay(runId.get(), -1).stream().map(StreamEvent::type).toList()
        );
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream(
                "memory", Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new ProductionProperties.Session(true, "memory", 20, 2000, Duration.ofMinutes(1), 3),
            null, null, null, null, null, null
        );
    }
}
