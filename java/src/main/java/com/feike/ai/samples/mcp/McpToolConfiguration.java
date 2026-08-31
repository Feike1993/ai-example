package com.feike.ai.samples.mcp;

import com.feike.ai.samples.tools.CpkTools;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 同进程工具源：始终注册 {@code mcpServerTools}，与 MCP Client 并存。
 * <p>
 * 不按 {@code app.ai.mcp.mode=inprocess} 条件装配——否则默认 remote 启动时本地 Bean 缺失，
 * 运行时就无法切到 inprocess。样例 inprocess 路径把本 Provider 直挂 ChatClient，
 * 不必开启主应用的 MCP Server HTTP（{@code spring.ai.mcp.server.enabled} 可仍为 false）。
 */
@Configuration
public class McpToolConfiguration {

    /**
     * 天气 / 加法 / CPK 演示工具，供 inprocess 模式与（可选）本机 MCP Server 共用。
     *
     * @param demoTools 基础演示工具
     * @param cpkTools  CPK 相关演示工具
     * @return ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider mcpServerTools(DemoTools demoTools, CpkTools cpkTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(demoTools, cpkTools)
            .build();
    }
}
