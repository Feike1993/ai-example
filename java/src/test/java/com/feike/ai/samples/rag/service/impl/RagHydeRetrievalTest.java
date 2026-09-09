package com.feike.ai.samples.rag.service.impl;

import com.feike.ai.samples.rag.service.RagSampleService;
import com.feike.ai.samples.rag.model.ChunkingStrategyEnum;
import com.feike.ai.samples.rag.model.CitationModeEnum;
import com.feike.ai.samples.rag.model.QueryExpansionEnum;
import com.feike.ai.samples.rag.model.RetrievalModeEnum;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * HyDE：假想文档用于检索查询，不得进入 sources。
 */
@DisplayName("RagSampleService HyDE")
class RagHydeRetrievalTest {

    private static final String HYPO =
        "假想段落：MCP 是一种工具接入协议，Host 通过 Client 连接远端 Server 暴露工具。";

    @Test
    void hydeSearchesWithHypotheticalTextAndSourcesStayReal() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document realChunk = Document.builder()
            .id("real-1")
            .text("真实语料：MCP 拆分 Host/Client/Server，传输可用 Streamable HTTP。")
            .metadata(Map.of("source", "02-mcp.md", "corpus", "ai-example-demo"))
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.plainClient(anyString())).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(HYPO);

        // fuse-with-original=false：只走假想段落一路，便于断言检索 query
        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(
                true, 4, 400, 1, true,
                new AiProperties.Rag.Hybrid(true, 60, 4, false),
                new AiProperties.Rag.Hyde(true, false),
                new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
            ),
            null
        );

        RagSampleService.RetrievalBundle bundle = service.retrieveExpanded(
            "MCP 是啥？",
            4,
            RetrievalModeEnum.VECTOR,
            QueryExpansionEnum.HYDE,
            "deepseek",
            RagSampleService.CORPUS_DEMO
        );

        assertEquals(QueryExpansionEnum.HYDE, bundle.expansion());
        assertEquals(HYPO, bundle.hypotheticalDocument());
        assertEquals(1, bundle.hits().size());
        assertEquals("real-1", bundle.hits().get(0).getId());
        assertFalse(bundle.hits().get(0).getText().contains("假想段落"));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        assertTrue(
            captor.getAllValues().stream().anyMatch(req -> HYPO.equals(req.getQuery())),
            "应用假想文档作为 similaritySearch query"
        );
    }

    @Test
    void queryWithHydeReturnsExpansionFieldsWithoutHypoInSources() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document realChunk = Document.builder()
            .id("real-2")
            .text("真实 chunk 正文")
            .metadata(Map.of("source", "demo.md"))
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.plainClient(anyString())).thenReturn(client);
        // HyDE 假想走 content()；最终答案走 chatResponse()
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(HYPO);
        when(client.prompt().system(anyString()).user(anyString()).call().chatResponse())
            .thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("基于真实 chunk 的回答"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1, 2, null)).build()
            ));

        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(
                true, 4, 400, 1, true,
                new AiProperties.Rag.Hybrid(true, 60, 4, false),
                new AiProperties.Rag.Hyde(true, false),
                new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
            ),
            null
        );

        RagSampleService.RagQueryResult result = service.query(
            "一期都学了哪些？",
            "deepseek",
            null,
            RetrievalModeEnum.VECTOR,
            null,
            "hyde"
        );

        assertEquals("hyde", result.queryExpansion());
        assertNotNull(result.hypotheticalDocument());
        assertTrue(result.hypotheticalDocument().contains("假想段落"));
        assertEquals(1, result.sources().size());
        assertFalse(result.sources().get(0).excerpt().contains("假想段落"));
        assertEquals("基于真实 chunk 的回答", result.answer());
    }

    @Test
    void resolveExpansionPrefersExplicitOverRewriteFlag() {
        assertEquals(
            QueryExpansionEnum.HYDE,
            RagSampleService.resolveExpansion("hyde", true)
        );
        assertEquals(
            QueryExpansionEnum.REWRITE,
            RagSampleService.resolveExpansion(null, true)
        );
        assertEquals(
            QueryExpansionEnum.NONE,
            RagSampleService.resolveExpansion("none", true)
        );
    }

    @Test
    void compareExpansionReturnsThreeViews() {
        VectorStore vectorStore = mock(VectorStore.class);
        Document realChunk = Document.builder()
            .id("c1")
            .text("chunk")
            .metadata(Map.of("source", "a.md"))
            .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk));

        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.plainClient(anyString())).thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
            .thenReturn("改写短句 MCP")
            .thenReturn(HYPO);

        RagSampleService service = new RagSampleServiceImpl(
            vectorStore,
            registry,
            new AiProperties.Rag(
                true, 4, 400, 1, true,
                new AiProperties.Rag.Hybrid(true, 60, 4, false),
                new AiProperties.Rag.Hyde(true, false),
                new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
            ),
            null
        );

        RagSampleService.ExpansionCompareResult compare = service.queryCompareExpansion(
            "MCP?",
            "deepseek",
            2,
            RetrievalModeEnum.VECTOR
        );

        assertEquals("none", compare.none().queryExpansion());
        assertEquals("rewrite", compare.rewrite().queryExpansion());
        assertEquals("hyde", compare.hyde().queryExpansion());
        assertNotNull(compare.hyde().hypotheticalDocument());
    }
}
