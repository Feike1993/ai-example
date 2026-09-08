package com.feike.ai.production.chat;

import com.feike.ai.production.ProductionProperties;
import com.feike.ai.production.rag.ProductionSource;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.sse.InMemoryRunEventLog;
import com.feike.ai.production.sse.SseRunExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 走真实 HTTP 管线验证 SSE 报文：事件名、id 序号、终态、断线续传、过期 run。
 * <p>
 * 用 standalone MockMvc 而不是整个 {@code @SpringBootTest}：这里要断言的是传输层契约，
 * 不该被向量库和模型网关的可用性绑架。
 */
@DisplayName("Production SSE MVC")
class ProductionSseMvcTest {

    private static final Pattern RUN_ID = Pattern.compile("\"runId\":\"([0-9a-f-]+)\"");

    private final ProductionRetrievalService retrieval = mock(ProductionRetrievalService.class);
    private final ProductionAnswerGenerator generator = mock(ProductionAnswerGenerator.class);
    private final ProductionIngestService ingestService = mock(ProductionIngestService.class);
    private final InMemoryRunEventLog eventLog = new InMemoryRunEventLog();

    private SseRunExecutor runExecutor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductionProperties properties = new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream("memory", Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10))
        );
        runExecutor = new SseRunExecutor(eventLog, JsonMapper.builder().build(), properties);
        ProductionChatService chatService = new ProductionChatService(retrieval, generator, properties);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ProductionChatController(chatService, ingestService, runExecutor))
            .build();
    }

    @AfterEach
    void tearDown() {
        runExecutor.shutdown();
    }

    @Test
    void happyPathShouldEndWithDoneAndMonotonicIds() throws Exception {
        stubRetrieval();
        stubStream("答", "案");

        String body = streamBody("/api/v1/chat/stream?question=%E4%BB%80%E4%B9%88%E6%98%AF%20RAG");

        assertTrue(body.contains("event:meta"), body);
        assertTrue(body.contains("event:sources"), body);
        assertTrue(body.contains("event:delta"), body);
        assertTrue(body.contains("event:usage"), body);
        assertTrue(body.contains("event:done"), body);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L), idsOf(body));
    }

    @Test
    void upstreamFailureShouldEmitErrorEventInsteadOfBareDisconnect() throws Exception {
        stubRetrieval();
        doThrow(new IllegalStateException("模型网关 502"))
            .when(generator).stream(anyString(), any(), any(), any());

        String body = streamBody("/api/v1/chat/stream?question=x");

        assertTrue(body.contains("event:error"), body);
        assertTrue(body.contains("upstream_error"), body);
        assertFalse(body.contains("event:done"), body);
    }

    @Test
    void resumeShouldReplayOnlyEventsAfterLastEventId() throws Exception {
        stubRetrieval();
        stubStream("一", "二", "三");
        String first = streamBody("/api/v1/chat/stream?question=x");
        String runId = runIdOf(first);

        // 客户端声称只收到了 seq<=1（meta 与 sources），其余需要补齐
        MvcResult result = mockMvc.perform(get("/api/v1/runs/{runId}/stream", runId)
                .header("Last-Event-ID", "1"))
            .andExpect(request().asyncStarted())
            .andReturn();
        String replayed = awaitBody(result);

        assertFalse(replayed.contains("event:meta"), replayed);
        assertFalse(replayed.contains("event:sources"), replayed);
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L), idsOf(replayed));
        assertTrue(replayed.contains("event:done"), replayed);
    }

    @Test
    void resumeOfUnknownRunShouldBeGone() throws Exception {
        mockMvc.perform(get("/api/v1/runs/{runId}/stream", "not-a-run"))
            .andExpect(status().isGone());
    }

    private void stubRetrieval() {
        Document doc = Document.builder()
            .id("doc-1")
            .text("RAG 是检索增强生成。")
            .metadata(Map.of("source", "03-rag.md"))
            .build();
        when(retrieval.retrieve(anyString(), any())).thenReturn(
            new ProductionRetrievalService.RetrievalResult(
                List.of(doc),
                List.of(new ProductionSource("doc-1", "03-rag.md", "RAG 是检索增强生成。", null)),
                false,
                "hybrid"
            ));
    }

    private void stubStream(String... chunks) {
        doAnswer(invocation -> {
            Consumer<String> onChunk = invocation.getArgument(3);
            for (String chunk : chunks) {
                onChunk.accept(chunk);
            }
            return null;
        }).when(generator).stream(anyString(), any(), any(), any());
    }

    private String streamBody(String uri) throws Exception {
        MvcResult result = mockMvc.perform(get(uri))
            .andExpect(request().asyncStarted())
            .andReturn();
        return awaitBody(result);
    }

    /**
     * SseEmitter 走的是 complete 而非 dispatch，MockMvc 的 getAsyncResult 等不到；
     * 这里直接轮询响应体，直到出现终态事件或超时。
     */
    private static String awaitBody(MvcResult result) throws UnsupportedEncodingException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = result.getResponse().getContentAsString();
            if (body.contains("event:done") || body.contains("event:error")) {
                return body;
            }
            Thread.sleep(20L);
        }
        return body;
    }

    private static String runIdOf(String body) {
        Matcher matcher = RUN_ID.matcher(body);
        assertTrue(matcher.find(), "meta 事件里应带 runId：" + body);
        return matcher.group(1);
    }

    private static List<Long> idsOf(String body) {
        return body.lines()
            .filter(line -> line.startsWith("id:"))
            .map(line -> Long.parseLong(line.substring(3).trim()))
            .toList();
    }
}
