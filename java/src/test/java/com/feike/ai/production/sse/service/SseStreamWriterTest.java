package com.feike.ai.production.sse.service;

import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 契约的硬约束：seq 递增、终态唯一、终态后不再发事件、断开后仍入日志。
 */
@DisplayName("SseStreamWriter")
class SseStreamWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void shouldAssignMonotonicSeqStartingAtZero() {
        RecordingSink sink = new RecordingSink();
        InMemoryRunEventLogDAOImpl log = new InMemoryRunEventLogDAOImpl();
        SseStreamWriter writer = new SseStreamWriter("run-1", sink, log, JSON);

        writer.meta(Map.of("question", "你好"));
        writer.emit(StreamEventTypeEnum.SOURCES, Map.of("sources", List.of()));
        writer.delta("答");
        writer.delta("案");
        writer.done(Map.of());

        assertEquals(
            List.of(
                StreamEventTypeEnum.META,
                StreamEventTypeEnum.SOURCES,
                StreamEventTypeEnum.DELTA,
                StreamEventTypeEnum.DELTA,
                StreamEventTypeEnum.DONE
            ),
            sink.types()
        );
        for (int i = 0; i < sink.written().size(); i++) {
            assertEquals(i, sink.written().get(i).seq(), "第 " + i + " 条事件 seq 应等于下标");
        }
    }

    @Test
    void metaShouldCarryRunId() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-meta", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        writer.meta(null);

        assertTrue(sink.written().getFirst().data().contains("run-meta"));
    }

    @Test
    void shouldIgnoreEventsAfterDone() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-2", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        writer.done(Map.of());
        writer.delta("迟到的增量");
        writer.done(Map.of());
        writer.error("late", "迟到的错误");

        assertEquals(List.of(StreamEventTypeEnum.DONE), sink.types());
        assertTrue(writer.terminated());
    }

    @Test
    void shouldIgnoreEventsAfterError() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-3", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        writer.error("upstream_error", "模型超时");
        writer.delta("不该出现");
        writer.done(Map.of());

        assertEquals(List.of(StreamEventTypeEnum.ERROR), sink.types());
        assertTrue(sink.written().getFirst().data().contains("upstream_error"));
    }

    @Test
    void terminalTypesMustNotGoThroughEmit() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-4", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        assertThrows(IllegalArgumentException.class, () -> writer.emit(StreamEventTypeEnum.DONE, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> writer.emit(StreamEventTypeEnum.ERROR, Map.of()));
    }

    @Test
    void shouldRecordFinalStateInEventLog() {
        InMemoryRunEventLogDAOImpl log = new InMemoryRunEventLogDAOImpl();
        SseStreamWriter writer = new SseStreamWriter("run-5", new RecordingSink(), log, JSON);

        assertEquals(RunStateEnum.PENDING, log.snapshot("run-5").orElseThrow().state());
        writer.delta("x");
        assertEquals(RunStateEnum.STREAMING, log.snapshot("run-5").orElseThrow().state());
        writer.done(Map.of());

        RunSnapshot snapshot = log.snapshot("run-5").orElseThrow();
        assertEquals(RunStateEnum.DONE, snapshot.state());
        assertEquals(1, snapshot.lastSeq());
    }

    @Test
    void shouldKeepLoggingAfterClientDisconnects() {
        RecordingSink sink = new RecordingSink();
        InMemoryRunEventLogDAOImpl log = new InMemoryRunEventLogDAOImpl();
        SseStreamWriter writer = new SseStreamWriter("run-6", sink, log, JSON);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        writer.onDisconnect(() -> disconnected.set(true));

        writer.delta("先收到这段");
        sink.breakConnection();
        writer.delta("断开后的这段只入日志");
        writer.done(Map.of());

        assertTrue(disconnected.get(), "断开应触发取消回调");
        assertEquals(1, sink.written().size(), "断开后不应再写出");
        // 日志里仍是完整的三条，重连才能补齐
        assertEquals(3, log.replay("run-6", -1).size());
        assertEquals(RunStateEnum.DONE, log.snapshot("run-6").orElseThrow().state());
    }

    @Test
    void cancelShouldMarkRunCancelledWithoutTerminalEvent() {
        RecordingSink sink = new RecordingSink();
        InMemoryRunEventLogDAOImpl log = new InMemoryRunEventLogDAOImpl();
        SseStreamWriter writer = new SseStreamWriter("run-7", sink, log, JSON);

        writer.delta("半截");
        writer.cancel();

        assertEquals(List.of(StreamEventTypeEnum.DELTA), sink.types());
        assertTrue(sink.completed());
        assertEquals(RunStateEnum.CANCELLED, log.snapshot("run-7").orElseThrow().state());
    }

    @Test
    void heartbeatShouldNotConsumeSeq() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-8", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        writer.heartbeat();
        writer.delta("a");
        writer.heartbeat();

        assertEquals(2, sink.heartbeats());
        assertEquals(0, sink.written().getFirst().seq());
    }

    @Test
    void emptyDeltaShouldBeSkipped() {
        RecordingSink sink = new RecordingSink();
        SseStreamWriter writer = new SseStreamWriter("run-9", sink, new InMemoryRunEventLogDAOImpl(), JSON);

        writer.delta("");
        writer.delta(null);

        assertTrue(sink.written().isEmpty());
        assertFalse(writer.terminated());
    }
}
