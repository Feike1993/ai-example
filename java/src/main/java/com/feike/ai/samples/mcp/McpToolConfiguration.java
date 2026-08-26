package com.feike.ai.samples.mcp;

import com.feike.ai.samples.tools.CpkTools;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把现有 {@code @Tool} 注册给 MCP Server；Boot Starter 会扫描本 Bean 并对外暴露。
 */
@Configuration
public class McpToolConfiguration {

    /**
     * MCP Server 工具源：天气 / 加法 / CPK 演示工具。
     *
     * @param demoTools 基础演示工具
     * @param cpkTools  CPK 相关演示工具
     * @return 供 MCP Server 与本进程 Client 样例复用的 ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider mcpServerTools(DemoTools demoTools, CpkTools cpkTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(demoTools, cpkTools)
            .build();
    }
}
