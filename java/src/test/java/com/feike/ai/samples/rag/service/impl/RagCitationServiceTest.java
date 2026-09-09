package com.feike.ai.samples.rag.service.impl;

import com.feike.ai.samples.rag.service.RagSampleService;
import com.feike.ai.samples.rag.model.ChunkingStrategyEnum;
import com.feike.ai.samples.rag.model.CitationModeEnum;
import com.feike.ai.samples.rag.model.QueryExpansionEnum;
import com.feike.ai.samples.rag.model.RetrievalModeEnum;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.core.rag.CitationValidator;
import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.samples.structured.service.StructuredOutputInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * citationMode=required：合法引用通过；错误 sourceId → citationValid=false。
 */
@DisplayName("RagSampleService citation")
class RagCitationServiceTest {

    @Test
    void shouldAcceptValidCitations() {
        Document hit = Document.builder()
            .id("c1")
            .text("第一期包含 Chat 与 Agent")
            .metadata(Map.of("source", "demo.md"))
            .build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.plainClient(anyString())).thenReturn(mock(ChatClient.class));

        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        when(invoker.invoke(any(), anyString(), anyString(), eq(CitationValidator.GroundedAnswer.class)))
            .thenReturn(new StructuredOutputInvoker.InvokeResult<>(
                new CitationValidator.GroundedAnswer(
                    "第一期学了 Chat",
                    List.of(new CitationValidator.Citation("c1", "Chat 与 Agent"))
                ),
                new TokenUsageDTO(1, 2, 3)
            ));

        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            null,
            invoker
        );

        RagSampleService.RagQueryResult result = service.query(
            "第一期学了什么？", "deepseek", null,
            RetrievalModeEnum.VECTOR, null, null, null, "required"
        );

        assertTrue(result.citationValid());
        assertEquals("required", result.citationMode());
        assertEquals(1, result.citations().size());
        assertEquals("c1", result.citations().getFirst().sourceId());
        assertEquals("第一期学了 Chat", result.answer());
    }

    @Test
    void shouldRefuseWhenSourceIdUnknown() {
        Document hit = Document.builder()
            .id("c1")
            .text("片段")
            .metadata(Map.of("source", "demo.md"))
            .build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.plainClient(anyString())).thenReturn(mock(ChatClient.class));

        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        when(invoker.invoke(any(), anyString(), anyString(), eq(CitationValidator.GroundedAnswer.class)))
            .thenReturn(new StructuredOutputInvoker.InvokeResult<>(
                new CitationValidator.GroundedAnswer(
                    "编造答案",
                    List.of(new CitationValidator.Citation("ghost", "假"))
                ),
                new TokenUsageDTO(1, 1, 2)
            ));

        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            null,
            invoker
        );

        RagSampleService.RagQueryResult result = service.query(
            "问题", "deepseek", null,
            RetrievalModeEnum.VECTOR, null, null, null, "required"
        );

        assertFalse(result.citationValid());
        assertEquals(RagSampleService.CITATION_REFUSAL, result.answer());
        assertEquals(1, result.citations().size());
    }

    @Test
    void shouldAcceptAliasCitationAfterResolve() {
        Document hit = Document.builder()
            .id("c1")
            .text("第一期包含 Chat 与 Agent")
            .metadata(Map.of("source", "demo.md"))
            .build();
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        when(registry.plainClient(anyString())).thenReturn(mock(ChatClient.class));

        // 模拟模型按 prompt 返回 C1，而非向量库 id
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        when(invoker.invoke(any(), anyString(), anyString(), eq(CitationValidator.GroundedAnswer.class)))
            .thenReturn(new StructuredOutputInvoker.InvokeResult<>(
                new CitationValidator.GroundedAnswer(
                    "第一期学了 Chat",
                    List.of(new CitationValidator.Citation("C1", "Chat 与 Agent"))
                ),
                new TokenUsageDTO(1, 2, 3)
            ));

        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            null,
            invoker
        );

        RagSampleService.RagQueryResult result = service.query(
            "第一期学了什么？", "deepseek", null,
            RetrievalModeEnum.VECTOR, null, null, null, "required"
        );

        assertTrue(result.citationValid());
        assertEquals("c1", result.citations().getFirst().sourceId());
    }
}
