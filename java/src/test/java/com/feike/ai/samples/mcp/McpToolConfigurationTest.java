package com.feike.ai.samples.mcp;

import com.feike.ai.samples.tools.CpkTools;
import com.feike.ai.samples.tools.DemoTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具注册冒烟：不启动 HTTP / 不调模型。
 */
@DisplayName("McpToolConfiguration")
class McpToolConfigurationTest {

    @Test
    void shouldExposeDemoAndCpkTools() {
        ToolCallbackProvider provider = new McpToolConfiguration()
            .mcpServerTools(new DemoTools(), new CpkTools());
        Set<String> names = Arrays.stream(provider.getToolCallbacks())
            .map(cb -> cb.getToolDefinition().name())
            .collect(Collectors.toSet());
        assertFalse(names.isEmpty());
        assertTrue(names.stream().anyMatch(n -> n.toLowerCase().contains("weather") || n.contains("Weather") || n.contains("天气") || n.equals("getWeather")),
            "应包含天气工具，实际: " + names);
        assertTrue(names.stream().anyMatch(n -> n.equals("add") || n.toLowerCase().contains("add")),
            "应包含加法工具，实际: " + names);
    }

    @Test
    void toolDefinitionsShouldHaveNonBlankDescription() {
        ToolCallbackProvider provider = new McpToolConfiguration()
            .mcpServerTools(new DemoTools(), new CpkTools());
        for (ToolCallback callback : provider.getToolCallbacks()) {
            assertFalse(callback.getToolDefinition().description().isBlank());
        }
    }
}
