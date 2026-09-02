package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CitationValidator：合法 / 空 / 错误 sourceId。
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
}
