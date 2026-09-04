package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.samples.memory.MemorySampleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * memory_rewrite：记忆 hints 不进 RAG sources；compare-memory 双路 empty 标志。
 */
@DisplayName("RagSampleService memory loop")
class RagMemoryLoopTest {

    private static final AiProperties.Rag RAG = new AiProperties.Rag(
        true, 4, 400, 1, true,
        new AiProperties.Rag.Hybrid(true, 60, 4, false),
        new AiProperties.Rag.Hyde(true, true),
        new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
    );

    @Test
    void memoryRewriteShouldKeepMemoryOutOfRagSources() {
        Document ragHit = Document.builder()
            .id("rag-1")
            .text("第一期包含 Chat 与 Agent")
            .metadata(Map.of("source", "demo.md", "corpus", RagSampleService.CORPUS_DEMO))
            .build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(ragHit));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        ChatClient client = mockChatClientReturning("一期 Chat Agent 能力清单");
        when(registry.plainClient(anyString())).thenReturn(client);

        MemorySampleService memory = mock(MemorySampleService.class);
        when(memory.recall(anyString(), any(), anyInt())).thenReturn(
            new MemorySampleService.RecallResult(
                "demo",
                List.of(new MemorySampleService.SourceView("mem-1", "用户在学第一期", Map.of())),
                false,
                null
            )
        );

        RagSampleService service = new RagSampleService(
            vectorStore, registry, RAG, null, null, memory, new AiProperties.Memory(4, "demo", 0.92, 5)
        );

        RagSampleService.RagQueryResult result = service.query(
            "一期都学了哪些？", "deepseek", null,
            RagSampleService.RetrievalMode.vector, null, "memory_rewrite", null, null, "demo", 4
        );

        assertEquals("memory_rewrite", result.queryExpansion());
        assertFalse(result.memoryHints().isEmpty());
        assertEquals("mem-1", result.memoryHints().getFirst().id());
        assertTrue(result.sources().stream().noneMatch(s -> "mem-1".equals(s.id())));
        assertEquals(1, result.sources().size());
        assertEquals("rag-1", result.sources().getFirst().id());
        assertEquals("一期 Chat Agent 能力清单", result.rewrittenQuery());
    }

    @Test
    void compareMemoryShouldExposeBothEmptyFlagsWithoutGenerating() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        MemorySampleService memory = mock(MemorySampleService.class);
        when(memory.recall(anyString(), any(), any())).thenReturn(
            new MemorySampleService.RecallResult(
                "demo",
                List.of(new MemorySampleService.SourceView("mem-1", "喜欢烤鸭", Map.of())),
                false,
                null
            )
        );

        RagSampleService service = new RagSampleService(
            vectorStore, registry, RAG, null, null, memory, new AiProperties.Memory(4, "demo", 0.92, 5)
        );

        RagSampleService.MemoryRagCompareResult compare = service.queryCompareMemory(
            "我喜欢吃什么？", "deepseek", null, "demo", 4, false
        );

        assertTrue(compare.rag().retrievalEmpty());
        assertFalse(compare.memory().retrievalEmpty());
        assertFalse(compare.generateAnswers());
        assertEquals(1, compare.memory().sources().size());
    }

    private static ChatClient mockChatClientReturning(String text) {
        ChatClient client = mock(ChatClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ChatResponse response = new ChatResponse(
            List.of(new Generation(new AssistantMessage(text))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1, 2, null)).build()
        );
        when(client.prompt().system(anyString()).user(anyString()).call().chatResponse()).thenReturn(response);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(text);
        return client;
    }
}
