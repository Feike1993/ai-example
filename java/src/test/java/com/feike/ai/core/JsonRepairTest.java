package com.feike.ai.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JsonRepair} 的围栏剥离与引号修复单测。
 */
@DisplayName("JsonRepair")
class JsonRepairTest {

    @Test
    void shouldStripMarkdownFence() {
        String raw = """
            ```json
            {"title":"登录失败"}
            ```
            """;
        assertEquals("{\"title\":\"登录失败\"}", JsonRepair.stripMarkdownFence(raw));
    }

    @Test
    void shouldEscapeInnerQuotes() {
        String broken = "{\"summary\":\"用户说\"立刻修好\"}";
        String repaired = JsonRepair.repairUnescapedQuotes(broken);
        assertEquals("{\"summary\":\"用户说\\\"立刻修好\"}", repaired);
    }
}
