package com.feike.ai.samples.tools.manager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CpkTools")
class CpkToolsTest {

    @Test
    void bothLimitsMissingShouldFailFast() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> CpkTools.getSubgroupCPKInfo(List.of(24.1, 24.2, 24.0), 5, null, null)
        );
        assertTrue(ex.getMessage().contains("USL 与 LSL"));
    }
}
