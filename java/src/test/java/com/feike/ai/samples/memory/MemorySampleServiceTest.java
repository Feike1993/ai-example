package com.feike.ai.samples.memory;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆：空召回短路、精确去重、相似合并。
 */
@DisplayName("MemorySampleService")
class MemorySampleServiceTest {

    private static AiProperties props() {
        return new AiProperties(
            "deepseek",
            0.2,
            Map.of(),
            new AiProperties.Structured(2, true, true, true, 200, false),
            new AiProperties.Agent(8),
            "dashscope",
            new AiProperties.Embedding("text-embedding-v3", 1024),
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false)),
            new AiProperties.ContextSettings(24, 2000, 6, "memory"),
            new AiProperties.MultiAgent(4, 6),
            new AiProperties.Memory(4, "demo", 0.92)
        );
    }

    @Test
    void chatShouldRefuseWhenRecallEmpty() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        MemorySampleService service = new MemorySampleService(vectorStore, registry, props());
        MemorySampleService.MemoryChatResult result = service.chat("我喜欢什么？", "demo", "deepseek", null);

        assertTrue(result.retrievalEmpty());
        assertEquals(MemorySampleService.EMPTY_REFUSAL, result.answer());
        verify(registry, never()).plainClient(any());
    }

    @Test
    void rememberShouldWriteDocument() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        MemorySampleService service = new MemorySampleService(
            vectorStore,
            mock(LlmProviderRegistry.class),
            props()
        );

        MemorySampleService.RememberResult result = service.remember("喜欢北京烤鸭", null, null);
        assertEquals("demo", result.userId());
        assertFalse(result.duplicate());
        assertFalse(result.updated());
        verify(vectorStore).add(anyList());
    }

    @Test
    void rememberShouldSkipExactDuplicate() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document existing = Document.builder()
            .id("mem-1")
            .text("喜欢北京烤鸭")
            .score(0.99)
            .metadata(Map.of(
                MemorySampleService.META_CORPUS, MemorySampleService.CORPUS_MEMORY,
                MemorySampleService.META_USER_ID, "demo"
            ))
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(existing));

        MemorySampleService service = new MemorySampleService(
            vectorStore,
            mock(LlmProviderRegistry.class),
            props()
        );

        MemorySampleService.RememberResult result = service.remember("喜欢北京烤鸭", "demo", null);

        assertTrue(result.duplicate());
        assertFalse(result.updated());
        assertEquals("mem-1", result.id());
        verify(vectorStore, never()).add(anyList());
        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    void rememberShouldReplaceSimilarMemory() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document existing = Document.builder()
            .id("mem-old")
            .text("用户名叫小明，喜欢北京烤鸭")
            .score(0.95)
            .metadata(Map.of(
                MemorySampleService.META_CORPUS, MemorySampleService.CORPUS_MEMORY,
                MemorySampleService.META_USER_ID, "demo"
            ))
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(existing));

        MemorySampleService service = new MemorySampleService(
            vectorStore,
            mock(LlmProviderRegistry.class),
            props()
        );

        MemorySampleService.RememberResult result = service.remember("小明爱吃北京烤鸭", "demo", null);

        assertFalse(result.duplicate());
        assertTrue(result.updated());
        assertEquals("小明爱吃北京烤鸭", result.text());
        verify(vectorStore).delete(eq(List.of("mem-old")));
        verify(vectorStore).add(anyList());
    }

    @Test
    void recallShouldDedupeIdenticalSources() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document doc1 = Document.builder()
            .id("1")
            .text("用户名叫小明，喜欢北京烤鸭")
            .build();
        Document doc2 = Document.builder()
            .id("2")
            .text("用户名叫小明，喜欢北京烤鸭")
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc1, doc2, doc2));

        MemorySampleService service = new MemorySampleService(
            vectorStore,
            mock(LlmProviderRegistry.class),
            props()
        );

        MemorySampleService.RecallResult result = service.recall("喜欢吃什么", "demo", 4);

        assertEquals(1, result.sources().size());
        assertEquals("用户名叫小明，喜欢北京烤鸭", result.sources().getFirst().excerpt());
    }
}
