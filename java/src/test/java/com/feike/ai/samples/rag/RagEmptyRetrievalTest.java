package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空检索拒答：mock VectorStore 返回空 hits，验证短路与 retrievalEmpty。
 */
@DisplayName("RagSampleService 空检索")
class RagEmptyRetrievalTest {

    @Test
    void shouldSkipLlmAndRefuseWhenHitsEmpty() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        RagSampleService service = new RagSampleService(
            vectorStore,
            registry,
            new AiProperties.Rag(true, 4, 400, 1, true)
        );

        RagSampleService.RagQueryResult result = service.query("完全不存在的虚构关键词 xyz123", "deepseek", null);

        assertTrue(result.retrievalEmpty());
        assertEquals(RagSampleService.EMPTY_REFUSAL, result.answer());
        assertTrue(result.sources().isEmpty());
        assertNull(result.usage());
        verify(registry, never()).plainClient(anyString());
    }

    @Test
    void shouldTreatBelowMinSourcesAsEmpty() {
        Document hit = Document.builder()
            .id("c1")
            .text("仅一条弱相关片段")
            .metadata(Map.of("source", "demo.md"))
            .build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        // min-sources=2：单条命中仍视为空检索并短路
        RagSampleService service = new RagSampleService(
            vectorStore,
            registry,
            new AiProperties.Rag(true, 4, 400, 2, true)
        );

        RagSampleService.RagQueryResult result = service.query("弱相关问题", "deepseek", null);

        assertTrue(result.retrievalEmpty());
        assertEquals(RagSampleService.EMPTY_REFUSAL, result.answer());
        assertEquals(1, result.sources().size());
        verify(registry, never()).plainClient(anyString());
    }

    @Test
    void isRetrievalEmptyRespectsMinSources() {
        RagSampleService service = new RagSampleService(
            mock(VectorStore.class),
            mock(LlmProviderRegistry.class),
            new AiProperties.Rag(true, 4, 400, 2, false)
        );

        assertTrue(service.isRetrievalEmpty(List.of()));
        assertTrue(service.isRetrievalEmpty(List.of(Document.builder().text("a").build())));
        assertFalse(service.isRetrievalEmpty(List.of(
            Document.builder().text("a").build(),
            Document.builder().text("b").build()
        )));
    }

    @Test
    void streamAnswerShouldEmitRefusalWithoutLlmWhenEmpty() {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        RagSampleService service = new RagSampleService(
            mock(VectorStore.class),
            registry,
            new AiProperties.Rag(true, 4, 400, 1, true)
        );

        List<String> chunks = service.streamAnswer("无命中问题", "deepseek", List.of()).collectList().block();

        assertEquals(List.of(RagSampleService.EMPTY_REFUSAL), chunks);
        verify(registry, never()).plainClient(anyString());
    }
}
