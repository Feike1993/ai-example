package com.feike.ai.production.chat;

import com.feike.ai.production.ProductionProperties;
import com.feike.ai.production.lock.InMemorySessionLock;
import com.feike.ai.production.lock.SessionLock;
import com.feike.ai.production.rag.ProductionSource;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.session.FakeProductionChatSessionStore;
import com.feike.ai.production.session.SessionMessage;
import com.feike.ai.production.sse.EventSink;
import com.feike.ai.production.sse.InMemoryRunEventLog;
import com.feike.ai.production.sse.SseStreamWriter;
import com.feike.ai.production.sse.StreamEvent;
import com.feike.ai.production.sse.StreamEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层事件顺序：meta → sources → delta* → usage → done，异常收敛成单条 error；
 * 以及会话写入的两条规则：只有 done 才落库、落库失败不改变已发出的结论。
 */
@DisplayName("ProductionChatService")
class ProductionChatServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProductionRetrievalService retrieval = mock(ProductionRetrievalService.class);
    private final ProductionAnswerGenerator generator = mock(ProductionAnswerGenerator.class);

    /** 会话关闭：只验证事件契约，不牵扯存储。 */
    private final ProductionChatService service =
        new ProductionChatService(retrieval, generator, properties(false), null, null);

    @Test
    void shouldEmitContractOrderOnHappyPath() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
        stubStream("答", "案");

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), null, "什么是 RAG", "deepseek", null);

        assertEquals(
            List.of(
                StreamEventType.meta,
                StreamEventType.sources,
                StreamEventType.delta,
                StreamEventType.delta,
                StreamEventType.usage,
                StreamEventType.done
            ),
            sink.types()
        );
    }

    @Test
    void emptyRetrievalShouldRefuseWithoutCallingModel() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(empty());

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), null, "无关问题", null, null);

        verify(generator, never()).stream(anyString(), any(), any(), any(), any());
        assertEquals(
            List.of(
                StreamEventType.meta,
                StreamEventType.sources,
                StreamEventType.delta,
                StreamEventType.usage,
                StreamEventType.done
            ),
            sink.types()
        );
        assertTrue(sink.dataOf(StreamEventType.delta).contains("无法作答"));
        assertTrue(sink.dataOf(StreamEventType.sources).contains("\"retrievalEmpty\":true"));
    }

    @Test
    void upstreamFailureShouldCollapseIntoSingleErrorEvent() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
        doThrow(new IllegalStateException("模型网关 502"))
            .when(generator).stream(anyString(), any(), any(), any(), any());

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), null, "什么是 RAG", null, null);

        assertEquals(
            List.of(StreamEventType.meta, StreamEventType.sources, StreamEventType.error),
            sink.types()
        );
        String error = sink.dataOf(StreamEventType.error);
        assertTrue(error.contains("upstream_error"));
        assertTrue(error.contains("模型网关 502"));
    }

    @Test
    void retrievalFailureShouldAlsoBecomeErrorEvent() {
        when(retrieval.retrieve(anyString(), eq(3))).thenThrow(new IllegalStateException("pgvector 连接失败"));

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), null, "什么是 RAG", null, 3);

        assertEquals(List.of(StreamEventType.meta, StreamEventType.error), sink.types());
    }

    @Test
    void syncAnswerShouldSkipModelOnEmptyRetrieval() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(empty());

        ProductionChatService.ChatAnswer answer = service.answer(null, "无关问题", null, null);

        assertTrue(answer.retrievalEmpty());
        assertEquals(ProductionAnswerGenerator.EMPTY_REFUSAL, answer.answer());
        verify(generator, never()).generate(anyString(), any(), any(), any());
    }

    @Test
    void sessionEndpointsShouldFailFastWhenSessionsAreDisabled() {
        // 关掉会话时问答仍要能用，只是会话接口明确报「没装配」而不是 500
        assertThrows(SessionDisabledException.class, () -> service.history("s-x"));
        assertThrows(SessionDisabledException.class, () -> service.clearSession("s-x"));
    }

    @Test
    void disabledSessionShouldNotBlockPlainStreaming() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
        stubStream("答案");

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), "s-ignored", "什么是 RAG", null, null);

        assertTrue(sink.types().contains(StreamEventType.done));
        assertTrue(sink.dataOf(StreamEventType.done).contains("\"persisted\":false"));
    }

    @Nested
    @DisplayName("多轮会话")
    class WithSession {

        private final FakeProductionChatSessionStore store = new FakeProductionChatSessionStore();
        private final SessionLock lock = new InMemorySessionLock();
        private final ProductionChatService sessionService =
            new ProductionChatService(retrieval, generator, properties(true), store, lock);

        @Test
        void doneShouldPersistWholeTurnAndEchoSessionId() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            stubStream("答", "案");

            CollectingSink sink = new CollectingSink();
            sessionService.streamAnswer(writer(sink), "s-1", "什么是 RAG", null, null);

            assertTrue(sink.dataOf(StreamEventType.meta).contains("\"sessionId\":\"s-1\""));
            assertTrue(sink.dataOf(StreamEventType.done).contains("\"persisted\":true"));

            List<SessionMessage> history = store.history("s-1");
            assertEquals(2, history.size());
            assertEquals("user", history.get(0).role());
            assertEquals("什么是 RAG", history.get(0).content());
            assertEquals("assistant", history.get(1).role());
            assertEquals("答案", history.get(1).content());
            // 同一轮的两条消息共享 turnId，才能在事后把问答配成对
            assertEquals(history.get(0).turnId(), history.get(1).turnId());
        }

        @Test
        void secondTurnShouldSendPriorHistoryToModel() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            stubStream("第一轮");
            sessionService.streamAnswer(writer(new CollectingSink()), "s-2", "问题一", null, null);

            List<Message> captured = new ArrayList<>();
            doAnswer(invocation -> {
                captured.addAll(invocation.getArgument(3));
                Consumer<String> onChunk = invocation.getArgument(4);
                onChunk.accept("第二轮");
                return null;
            }).when(generator).stream(anyString(), any(), any(), any(), any());

            CollectingSink sink = new CollectingSink();
            sessionService.streamAnswer(writer(sink), "s-2", "问题二", null, null);

            assertEquals(List.of("问题一", "第一轮"), captured.stream().map(Message::getText).toList());
            assertTrue(sink.dataOf(StreamEventType.meta).contains("\"historyMessages\":2"));
            assertEquals(4, store.history("s-2").size());
        }

        @Test
        void cancelShouldNotLeaveOrphanUserMessage() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            doAnswer(invocation -> {
                Consumer<String> onChunk = invocation.getArgument(4);
                onChunk.accept("半截");
                // 客户端断开在生成层表现为线程中断，编排层据此按取消收尾
                Thread.currentThread().interrupt();
                onChunk.accept("再来一点");
                return null;
            }).when(generator).stream(anyString(), any(), any(), any(), any());

            CollectingSink sink = new CollectingSink();
            try {
                sessionService.streamAnswer(writer(sink), "s-3", "什么是 RAG", null, null);
            } finally {
                Thread.interrupted();
            }

            assertFalse(sink.types().contains(StreamEventType.done));
            assertTrue(store.history("s-3").isEmpty(), "取消的一轮不应留下任何消息");
        }

        @Test
        void upstreamErrorShouldNotPersist() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            doThrow(new IllegalStateException("模型网关 502"))
                .when(generator).stream(anyString(), any(), any(), any(), any());

            sessionService.streamAnswer(writer(new CollectingSink()), "s-4", "什么是 RAG", null, null);

            assertTrue(store.history("s-4").isEmpty());
        }

        @Test
        void persistFailureShouldStillReportDone() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            stubStream("答案");
            store.appendFailure = new IllegalStateException("数据库连接中断");

            CollectingSink sink = new CollectingSink();
            sessionService.streamAnswer(writer(sink), "s-5", "什么是 RAG", null, null);

            // 答案已经流到用户屏幕上了，此时改口发 error 只会让人不知道到底成没成
            assertTrue(sink.types().contains(StreamEventType.done));
            assertFalse(sink.types().contains(StreamEventType.error));
            assertTrue(sink.dataOf(StreamEventType.done).contains("\"persisted\":false"));
        }

        @Test
        void busySessionShouldEmitSessionBusyInsteadOfGenerating() {
            SessionLock.Handle held = lock.tryAcquire("s-6").orElseThrow();

            CollectingSink sink = new CollectingSink();
            sessionService.streamAnswer(writer(sink), "s-6", "什么是 RAG", null, null);
            held.close();

            assertEquals(List.of(StreamEventType.error), sink.types());
            assertTrue(sink.dataOf(StreamEventType.error).contains(ProductionChatService.SESSION_BUSY));
            verify(retrieval, never()).retrieve(anyString(), any());
        }

        @Test
        void busySessionShouldConflictOnSyncEndpoint() {
            try (SessionLock.Handle ignored = lock.tryAcquire("s-7").orElseThrow()) {
                assertThrows(
                    SessionBusyException.class,
                    () -> sessionService.answer("s-7", "什么是 RAG", null, null)
                );
            }
        }

        @Test
        void lockShouldBeReleasedAfterFailedTurn() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            doThrow(new IllegalStateException("模型网关 502"))
                .when(generator).stream(anyString(), any(), any(), any(), any());
            sessionService.streamAnswer(writer(new CollectingSink()), "s-8", "什么是 RAG", null, null);

            // 一轮失败把会话永久锁住，比这轮失败本身严重得多
            assertTrue(lock.tryAcquire("s-8").isPresent(), "失败后应释放会话锁");
        }

        @Test
        void clearShouldDropHistory() {
            when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
            stubStream("答案");
            sessionService.streamAnswer(writer(new CollectingSink()), "s-9", "什么是 RAG", null, null);

            assertTrue(sessionService.clearSession("s-9"));
            assertTrue(sessionService.history("s-9").isEmpty());
            assertFalse(sessionService.clearSession("s-9"));
        }
    }

    private void stubStream(String... chunks) {
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(4);
            for (String chunk : chunks) {
                onChunk.accept(chunk);
            }
            return null;
        }).when(generator).stream(anyString(), any(), any(), any(), any());
    }

    private SseStreamWriter writer(CollectingSink sink) {
        return new SseStreamWriter("run-test", sink, new InMemoryRunEventLog(), JSON);
    }

    private static ProductionProperties properties(boolean sessionEnabled) {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            null,
            new ProductionProperties.Session(sessionEnabled, "memory", 20, 2000, Duration.ofMinutes(1), 3)
        );
    }

    private static ProductionRetrievalService.RetrievalResult empty() {
        return new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "vector");
    }

    private static ProductionRetrievalService.RetrievalResult hits() {
        Document doc = Document.builder()
            .id("doc-1")
            .text("RAG 是检索增强生成。")
            .metadata(Map.of("source", "03-rag.md"))
            .build();
        return new ProductionRetrievalService.RetrievalResult(
            List.of(doc),
            List.of(new ProductionSource("doc-1", "03-rag.md", "RAG 是检索增强生成。", null)),
            false,
            "hybrid"
        );
    }

    /** 只关心事件类型与负载的极简出口。 */
    private static final class CollectingSink implements EventSink {

        private final List<StreamEvent> events = new ArrayList<>();

        @Override
        public void write(StreamEvent event) {
            events.add(event);
        }

        @Override
        public void heartbeat() {
        }

        @Override
        public void complete() {
        }

        List<StreamEventType> types() {
            return events.stream().map(StreamEvent::type).toList();
        }

        String dataOf(StreamEventType type) {
            return events.stream()
                .filter(event -> event.type() == type)
                .map(StreamEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到事件: " + type));
        }
    }
}
