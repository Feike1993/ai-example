package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 父子展开：同 parentId 去重，上下文用父全文。
 */
@DisplayName("RagSampleService parent expand")
class RagParentChildTest {

    @Test
    void expandParentsDedupesByParentId() {
        RagSampleService service = new RagSampleService(
            mock(VectorStore.class),
            mock(LlmProviderRegistry.class),
            new AiProperties.Rag(
                true, 4, 400, 1, true,
                new AiProperties.Rag.Hybrid(true, 60, 4, false),
                new AiProperties.Rag.Hyde(true, true),
                new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
            ),
            null
        );

        Document c1 = Document.builder()
            .id("c1")
            .text("子块一")
            .metadata(Map.of(
                "chunkRole", "child",
                "parentId", "p1",
                "parentText", "父块全文 MCP 协议说明",
                "source", "02-mcp.md"
            ))
            .build();
        Document c2 = Document.builder()
            .id("c2")
            .text("子块二")
            .metadata(Map.of(
                "chunkRole", "child",
                "parentId", "p1",
                "parentText", "父块全文 MCP 协议说明",
                "source", "02-mcp.md"
            ))
            .build();

        List<Document> expanded = service.expandParents(List.of(c1, c2));
        assertEquals(1, expanded.size());
        assertEquals("父块全文 MCP 协议说明", expanded.get(0).getText());
        assertEquals("parent", expanded.get(0).getMetadata().get("chunkRole"));
    }

    @Test
    void parseParentChildStrategy() {
        assertEquals(
            RagSampleService.ChunkingStrategy.parent_child,
            RagSampleService.parseChunkingStrategy("parent_child")
        );
        assertEquals(
            RagSampleService.ChunkingStrategy.parent_child,
            RagSampleService.parseChunkingStrategy("parent-child")
        );
    }

    @Test
    void toSourcesIncludesParentExcerpt() {
        RagSampleService service = new RagSampleService(
            mock(VectorStore.class),
            mock(LlmProviderRegistry.class),
            new AiProperties.Rag(
                true, 4, 400, 1, true,
                new AiProperties.Rag.Hybrid(true, 60, 4, false),
                new AiProperties.Rag.Hyde(true, true),
                new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)
            ),
            null
        );
        Document child = Document.builder()
            .id("c1")
            .text("短子块")
            .metadata(Map.of(
                "chunkRole", "child",
                "parentText", "很长的父块正文用于预览",
                "source", "a.md"
            ))
            .build();
        List<RagSampleService.SourceView> views = service.toSources(List.of(child));
        assertEquals(1, views.size());
        assertEquals("child", views.get(0).chunkRole());
        assertTrue(views.get(0).parentExcerpt().contains("父块"));
    }
}
