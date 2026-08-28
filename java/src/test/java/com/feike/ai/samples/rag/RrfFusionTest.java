package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("RrfFusion")
class RrfFusionTest {

    @Test
    void shouldFuseTwoListsWithRrf() {
        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            List.of("a", "b", "c"),
            List.of("b", "d"),
            60,
            4
        );

        assertEquals("b", fused.getFirst().id());
        assertEquals(2, fused.getFirst().vectorRank());
        assertEquals(1, fused.getFirst().keywordRank());
        assertEquals("a", fused.get(1).id());
        assertEquals(1, fused.get(1).vectorRank());
        assertNull(fused.get(1).keywordRank());
    }

    @Test
    void shouldRespectLimit() {
        List<RrfFusion.RankedId> fused = RrfFusion.fuse(
            List.of("a", "b", "c", "d"),
            List.of("e", "f"),
            60,
            2
        );
        assertEquals(2, fused.size());
    }
}
