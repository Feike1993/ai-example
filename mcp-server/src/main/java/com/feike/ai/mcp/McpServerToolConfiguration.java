package com.feike.ai.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link DemoTools} 注册给 MCP Server。
 */
@Configuration
public class McpServerToolConfiguration {

    /**
     * @param demoTools 演示工具
     * @return MCP Server 工具源
     */
    @Bean
    public ToolCallbackProvider mcpServerTools(DemoTools demoTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(demoTools)
            .build();
    }
}
