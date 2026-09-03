package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CitationValidator：严格校验 + resolve 归一化（别名 / 唯一文件名 / 序号）。
 */
@DisplayName("CitationValidator")
class CitationValidatorTest {

    @Test
    void shouldPassWhenAllSourceIdsInHits() {
        CitationValidator.Result result = CitationValidator.validate(
            List.of(
                new CitationValidator.Citation("c1", "摘录一"),
                new CitationValidator.Citation("c2", "摘录二")
            ),
            Set.of("c1", "c2", "c3")
        );
        assertTrue(result.valid());
    }

    @Test
    void shouldFailWhenCitationsEmpty() {
        CitationValidator.Result result = CitationValidator.validate(List.of(), Set.of("c1"));
        assertFalse(result.valid());
        assertTrue(result.detail().contains("为空"));
    }

    @Test
    void shouldFailWhenSourceIdUnknown() {
        CitationValidator.Result result = CitationValidator.validate(
            List.of(new CitationValidator.Citation("ghost", "假引用")),
            Set.of("c1")
        );
        assertFalse(result.valid());
        assertTrue(result.detail().contains("ghost"));
    }

    @Test
    void shouldResolveAliasToRealId() {
        List<RagSampleService.SourceView> sources = List.of(
            source("id-a", "a.md"),
            source("id-b", "b.md")
        );
        Map<String, String> aliases = CitationValidator.aliasMap(sources);
        assertEquals(Map.of("C1", "id-a", "C2", "id-b"), aliases);

        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(
            List.of(new CitationValidator.Citation("c1", "q"), new CitationValidator.Citation("C2", "q2")),
            aliases,
            sources
        );
        assertEquals("id-a", resolved.get(0).sourceId());
        assertEquals("id-b", resolved.get(1).sourceId());
        assertTrue(CitationValidator.validate(resolved, CitationValidator.idsOf(sources)).valid());
    }

    @Test
    void shouldResolveUniqueFilename() {
        List<RagSampleService.SourceView> sources = List.of(source("id-a", "03-rag.md"));
        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(
            List.of(new CitationValidator.Citation("03-rag.md", "摘录")),
            CitationValidator.aliasMap(sources),
            sources
        );
        assertEquals("id-a", resolved.getFirst().sourceId());
    }

    @Test
    void shouldNotGuessAmbiguousFilename() {
        List<RagSampleService.SourceView> sources = List.of(
            source("id-a", "03-rag.md"),
            source("id-b", "03-rag.md")
        );
        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(
            List.of(new CitationValidator.Citation("03-rag.md", "摘录")),
            CitationValidator.aliasMap(sources),
            sources
        );
        assertEquals("03-rag.md", resolved.getFirst().sourceId());
        assertFalse(CitationValidator.validate(resolved, CitationValidator.idsOf(sources)).valid());
    }

    @Test
    void shouldResolveOrdinal() {
        List<RagSampleService.SourceView> sources = List.of(
            source("id-a", "a.md"),
            source("id-b", "b.md")
        );
        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(
            List.of(
                new CitationValidator.Citation("1", "一"),
                new CitationValidator.Citation("[2]", "二")
            ),
            CitationValidator.aliasMap(sources),
            sources
        );
        assertEquals("id-a", resolved.get(0).sourceId());
        assertEquals("id-b", resolved.get(1).sourceId());
    }

    @Test
    void shouldKeepUnknownIdForValidateFailure() {
        List<RagSampleService.SourceView> sources = List.of(source("id-a", "a.md"));
        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(
            List.of(new CitationValidator.Citation("ghost", "假")),
            CitationValidator.aliasMap(sources),
            sources
        );
        assertEquals("ghost", resolved.getFirst().sourceId());
        assertFalse(CitationValidator.validate(resolved, CitationValidator.idsOf(sources)).valid());
    }

    private static RagSampleService.SourceView source(String id, String filename) {
        return new RagSampleService.SourceView(id, filename, "excerpt", Map.of());
    }
}
