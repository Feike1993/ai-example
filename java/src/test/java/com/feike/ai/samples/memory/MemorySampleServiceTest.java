package com.feike.ai.samples.memory;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.samples.context.ChatSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆：空召回短路、精确去重、相似合并、自动抽取。
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
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            new AiProperties.ContextSettings(24, 2000, 6, "memory"),
            new AiProperties.MultiAgent(4, 6),
            new AiProperties.Memory(4, "demo", 0.92, 5),
            new AiProperties.Mcp("inprocess", "dev-mcp-token"),
            null
        );
    }

    @SuppressWarnings("unchecked")
    private static MemorySampleService service(VectorStore vectorStore, LlmProviderRegistry registry) {
        ObjectProvider<ChatSessionStore> sessions = mock(ObjectProvider.class);
        when(sessions.getIfAvailable()).thenReturn(null);
        return new MemorySampleService(vectorStore, registry, props(), JsonMapper.builder().build(), sessions);
    }

    @Test
    void chatShouldRefuseWhenRecallEmpty() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        MemorySampleService svc = service(vectorStore, registry);
        MemorySampleService.MemoryChatResult result = svc.chat("我喜欢什么？", "demo", "deepseek", null);

        assertTrue(result.retrievalEmpty());
        assertEquals(MemorySampleService.EMPTY_REFUSAL, result.answer());
        verify(registry, never()).plainClient(any());
    }

    @Test
    void rememberShouldWriteDocument() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));

        MemorySampleService.RememberResult result = svc.remember("喜欢北京烤鸭", null, null);
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

        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));

        MemorySampleService.RememberResult result = svc.remember("喜欢北京烤鸭", "demo", null);

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

        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));

        MemorySampleService.RememberResult result = svc.remember("小明爱吃北京烤鸭", "demo", null);

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

        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));

        MemorySampleService.RecallResult result = svc.recall("喜欢吃什么", "demo", 4);

        assertEquals(1, result.sources().size());
        assertEquals("用户名叫小明，喜欢北京烤鸭", result.sources().getFirst().excerpt());
    }

    @Test
    void extractShouldRememberParsedFacts() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.plainClient(anyString())).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
            .thenReturn("[\"用户叫小明\",\"用户住在杭州\"]");

        MemorySampleService svc = service(vectorStore, registry);
        MemorySampleService.ExtractResult result = svc.extract(
            List.of(
                new MemorySampleService.DialogueMessage("user", "我叫小明，住在杭州"),
                new MemorySampleService.DialogueMessage("assistant", "好的")
            ),
            "demo",
            "s1",
            "deepseek"
        );

        assertEquals(2, result.facts().size());
        assertEquals(2, result.remembered().size());
        verify(vectorStore, atLeastOnce()).add(anyList());
    }

    @Test
    void parseFactListStripsFenceAndCaps() {
        MemorySampleService svc = service(mock(VectorStore.class), mock(LlmProviderRegistry.class));
        List<String> facts = svc.parseFactList(
            "```json\n[\"a\",\"b\",\"c\",\"d\"]\n```",
            2
        );
        assertEquals(List.of("a", "b"), facts);
    }

    @Test
    void recallShouldFilterBySimilarityThresholdWhenScorePresent() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document high = Document.builder().id("1").text("喜欢北京烤鸭").score(0.95).build();
        Document low = Document.builder().id("2").text("无关天气").score(0.2).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(high, low));

        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));
        MemorySampleService.RecallResult result = svc.recall("喜欢吃什么", "demo", 4, 0.5);

        assertEquals(1, result.sources().size());
        assertEquals("喜欢北京烤鸭", result.sources().getFirst().excerpt());
    }

    @Test
    void compareRecallShouldReturnThreeBranches() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document a = Document.builder().id("1").text("喜欢北京烤鸭").score(0.9).build();
        Document b = Document.builder().id("2").text("住在杭州").score(0.3).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(a, b));

        MemorySampleService svc = service(vectorStore, mock(LlmProviderRegistry.class));
        MemorySampleService.RecallCompareResult compare = svc.compareRecall(
            "喜欢什么",
            "demo",
            1,
            8,
            0.5
        );

        assertEquals(1, compare.lowTopKSize());
        assertEquals(8, compare.highTopKSize());
        assertEquals(0.5, compare.similarityThreshold());
        assertFalse(compare.lowTopK().empty());
        assertEquals(2, compare.highTopK().sources().size());
        assertEquals(1, compare.withThreshold().sources().size());
    }

    @Test
    void compareChatWithoutGenerateShouldSkipLlm() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);

        MemorySampleService svc = service(vectorStore, registry);
        MemorySampleService.ChatCompareResult compare = svc.compareChat(
            "任意",
            "demo",
            "deepseek",
            null,
            false
        );

        assertTrue(compare.withMemory().retrievalEmpty());
        verify(registry, never()).plainClient(any());
    }
}
