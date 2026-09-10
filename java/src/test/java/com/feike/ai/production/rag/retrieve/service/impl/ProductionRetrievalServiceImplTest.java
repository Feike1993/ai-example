package com.feike.ai.production.rag.retrieve.service.impl;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.rag.retrieve.manager.ProductionQueryExpander;
import com.feike.ai.production.rag.retrieve.model.ProductionRetrieveQuery;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionRetrievalService 查询扩展")
class ProductionRetrievalServiceImplTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ProductionQueryExpander expander = mock(ProductionQueryExpander.class);

    @Test
    void defaultNoneShouldSearchOriginalQuestionAndSkipExpander() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk()));
        ProductionRetrievalService service = service();

        ProductionRetrievalService.RetrievalResult result = service.retrieve("什么是 RAG", 4, "tenant-a");

        verify(expander, never()).rewrite(anyString(), any());
        verify(expander, never()).hypotheticalDocument(anyString(), any());
        assertEquals("none", result.queryExpansion());
        assertEquals("03-rag.md", result.sources().getFirst().source());
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertEquals("什么是 RAG", captor.getValue().getQuery());
    }

    @Test
    void hydeShouldSearchHypotheticalTextWhileSourcesStayRealChunks() {
        when(expander.hypotheticalDocument(eq("什么是 RAG"), eq("deepseek")))
            .thenReturn("检索增强生成把检索段落拼进提示词。");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk()));
        ProductionRetrievalService service = service();

        ProductionRetrievalService.RetrievalResult result = service.retrieve(
            new ProductionRetrieveQuery("什么是 RAG", 4, "tenant-a", "hyde", "deepseek")
        );

        assertEquals("hyde", result.queryExpansion());
        assertFalse(result.empty());
        assertEquals("03-rag.md", result.sources().getFirst().source());
        assertTrue(result.sources().getFirst().excerpt().contains("知识库原文"));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, org.mockito.Mockito.atLeastOnce()).similaritySearch(captor.capture());
        assertTrue(
            captor.getAllValues().stream().anyMatch(req -> "检索增强生成把检索段落拼进提示词。".equals(req.getQuery())),
            "至少一路向量检索应使用假想文档"
        );
    }

    @Test
    void rewriteShouldSearchRewrittenQuery() {
        when(expander.rewrite(eq("这个项目的 RAG 是怎么做的？"), any())).thenReturn("RAG 实现");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk()));
        ProductionRetrievalService service = service();

        ProductionRetrievalService.RetrievalResult result = service.retrieve(
            new ProductionRetrieveQuery("这个项目的 RAG 是怎么做的？", 4, "tenant-a", "rewrite", null)
        );

        assertEquals("rewrite", result.queryExpansion());
        assertEquals("RAG 实现", result.rewrittenQuery());
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertEquals("RAG 实现", captor.getValue().getQuery());
    }

    @Test
    void unknownExpansionShouldBehaveAsNone() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(realChunk()));
        ProductionRetrievalService service = service();

        ProductionRetrievalService.RetrievalResult result = service.retrieve(
            new ProductionRetrieveQuery("什么是 RAG", 4, "tenant-a", "memory_rewrite", null)
        );

        assertEquals("none", result.queryExpansion());
        verify(expander, never()).rewrite(anyString(), any());
    }

    private ProductionRetrievalServiceImpl service() {
        return new ProductionRetrievalServiceImpl(
            vectorStore,
            null,
            expander,
            new ProductionProperties(true, "prod-corpus", 4, 400, 1, false, 60, 4, null, null, null, null, null, null, null)
        );
    }

    private static Document realChunk() {
        return Document.builder()
            .id("doc-1")
            .text("知识库原文：RAG 是检索增强生成。")
            .metadata(Map.of("source", "03-rag.md"))
            .build();
    }
}
