package com.feike.ai.production.guardrail.service;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.rag.model.ProductionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ProductionGuardrail")
class ProductionGuardrailTest {

    private final ProductionGuardrail guardrail = new ProductionGuardrail(
        new ProductionProperties(true, "c", 4, 400, 1, true, 60, 4, null, null, null, null, null, null, null)
    );

    @Test
    void inputDenyShouldFailClosed() {
        assertThrows(GuardrailBlockedException.class, () -> guardrail.checkInput("含有违禁演示词"));
    }

    @Test
    void missingCitationShouldFailWhenSourcesExist() {
        List<ProductionSource> sources = List.of(
            new ProductionSource("doc-1", "a.md", "excerpt", null)
        );
        assertThrows(GuardrailBlockedException.class, () -> guardrail.checkCitations("没有引用的答案", sources));
    }

    @Test
    void aliasCitationShouldPass() {
        List<ProductionSource> sources = List.of(
            new ProductionSource("doc-1", "a.md", "excerpt", null)
        );
        assertDoesNotThrow(() -> guardrail.checkCitations("依据 [C1] 可以回答", sources));
    }
}
