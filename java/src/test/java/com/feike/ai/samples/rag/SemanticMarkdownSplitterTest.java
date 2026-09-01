package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义 Markdown 分块：标题边界与元数据。
 */
@DisplayName("SemanticMarkdownSplitter")
class SemanticMarkdownSplitterTest {

    @Test
    void splitsByHeadingAndKeepsHeadingMetadata() {
        SemanticMarkdownSplitter splitter = new SemanticMarkdownSplitter(400);
        Document doc = Document.builder()
            .text("""
                # 导读
                第一期介绍 Chat。

                ## MCP
                MCP 是工具协议。

                Function Calling 是模型能力。
                """)
            .metadata(Map.of("source", "demo.md"))
            .build();

        List<Document> chunks = splitter.apply(List.of(doc));
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> "semantic".equals(c.getMetadata().get("chunking"))));
        assertTrue(chunks.stream().anyMatch(c -> "MCP".equals(c.getMetadata().get("heading"))));
        assertTrue(chunks.stream().anyMatch(c -> c.getText().contains("工具协议")));
    }

    @Test
    void softMergeKeepsUnderTarget() {
        SemanticMarkdownSplitter splitter = new SemanticMarkdownSplitter(80);
        List<SemanticMarkdownSplitter.Segment> segs = List.of(
            new SemanticMarkdownSplitter.Segment("A", "短句一。"),
            new SemanticMarkdownSplitter.Segment("A", "短句二。")
        );
        List<SemanticMarkdownSplitter.Segment> merged = splitter.softMerge(segs);
        assertEquals(1, merged.size());
        assertTrue(merged.get(0).body().contains("短句一"));
        assertTrue(merged.get(0).body().contains("短句二"));
    }

    @Test
    void parseChunkingStrategyDefaultsToToken() {
        assertEquals(RagSampleService.ChunkingStrategy.token, RagSampleService.parseChunkingStrategy(null));
        assertEquals(RagSampleService.ChunkingStrategy.semantic, RagSampleService.parseChunkingStrategy("semantic"));
    }
}
