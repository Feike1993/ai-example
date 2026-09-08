package com.feike.ai.production.chat;

import com.feike.ai.production.ProductionProperties;
import com.feike.ai.production.rag.ProductionSource;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.sse.EventSink;
import com.feike.ai.production.sse.InMemoryRunEventLog;
import com.feike.ai.production.sse.SseStreamWriter;
import com.feike.ai.production.sse.StreamEvent;
import com.feike.ai.production.sse.StreamEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 编排层事件顺序：meta → sources → delta* → usage → done，异常收敛成单条 error。
 */
@DisplayName("ProductionChatService")
class ProductionChatServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProductionRetrievalService retrieval = mock(ProductionRetrievalService.class);
    private final ProductionAnswerGenerator generator = mock(ProductionAnswerGenerator.class);
    private final ProductionProperties properties =
        new ProductionProperties(true, "prod-corpus", 4, 400, 1, true, 60, 4, null);
    private final ProductionChatService service =
        new ProductionChatService(retrieval, generator, properties);

    @Test
    void shouldEmitContractOrderOnHappyPath() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(hits());
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("答");
            onChunk.accept("案");
            return null;
        }).when(generator).stream(anyString(), any(), any(), any());

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), "什么是 RAG", "deepseek", null);

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
        when(retrieval.retrieve(anyString(), any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "vector"));

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), "无关问题", null, null);

        verify(generator, never()).stream(anyString(), any(), any(), any());
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
            .when(generator).stream(anyString(), any(), any(), any());

        CollectingSink sink = new CollectingSink();
        service.streamAnswer(writer(sink), "什么是 RAG", null, null);

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
        service.streamAnswer(writer(sink), "什么是 RAG", null, 3);

        assertEquals(List.of(StreamEventType.meta, StreamEventType.error), sink.types());
    }

    @Test
    void syncAnswerShouldSkipModelOnEmptyRetrieval() {
        when(retrieval.retrieve(anyString(), any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(List.of(), List.of(), true, "vector"));

        ProductionChatService.ChatAnswer answer = service.answer("无关问题", null, null);

        assertTrue(answer.retrievalEmpty());
        assertEquals(ProductionAnswerGenerator.EMPTY_REFUSAL, answer.answer());
        verify(generator, never()).generate(anyString(), any(), any());
    }

    private SseStreamWriter writer(CollectingSink sink) {
        return new SseStreamWriter("run-test", sink, new InMemoryRunEventLog(), JSON);
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
