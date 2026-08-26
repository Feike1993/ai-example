package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 分块冒烟：不连 pgvector / 不调 Embedding。
 */
@DisplayName("RagTokenSplitter")
class RagTokenSplitterTest {

    @Test
    void shouldSplitLongMarkdownIntoMultipleChunks() {
        String body = """
            # 标题

            第一段介绍 RAG 与分块策略。分块过大丢精度，过小丢语义。

            ## 第二节

            Embedding 与 Chat Provider 应该分离。本仓用 DashScope text-embedding-v3。

            ## 第三节

            检索噪声会导致幻觉；前端展示 sources 便于核对答案出处。
            """.repeat(8);
        Document doc = Document.builder()
            .text(body)
            .metadata(Map.of("source", "demo.md", RagSampleService.META_CORPUS, RagSampleService.CORPUS_DEMO))
            .build();
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(120).build();
        List<Document> chunks = splitter.apply(List.of(doc));
        assertTrue(chunks.size() > 1, "长文应切出多个 chunk，实际=" + chunks.size());
        assertFalse(chunks.getFirst().getText().isBlank());
    }
}
