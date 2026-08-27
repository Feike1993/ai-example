package com.feike.ai.samples.mcp;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 样例：通过 MCP Server 注册的 {@link ToolCallbackProvider} 挂工具再聊天。
 * <p>
 * 同进程内 Server 已用 Streamable HTTP 暴露 {@code /mcp}；本 Service 直接复用注册到
 * MCP Server 的 ToolCallbackProvider，避免启动期 Client 连自己的鸡生蛋问题。
 * 生产环境应拆成独立 MCP Server，业务侧只用 MCP Client 拉远端工具。
 */
@Service
public class McpSampleService {

    private final LlmProviderRegistry registry;
    private final ToolCallbackProvider mcpTools;

    /**
     * @param registry LLM 工厂
     * @param mcpTools {@link McpToolConfiguration#mcpServerTools} 提供的 Provider
     */
    public McpSampleService(
        LlmProviderRegistry registry,
        @Qualifier("mcpServerTools") ToolCallbackProvider mcpTools
    ) {
        this.registry = registry;
        this.mcpTools = mcpTools;
    }

    /**
     * 列出 MCP Server 当前暴露的工具名。
     *
     * @return 工具名列表
     */
    public List<String> listToolNames() {
        return Arrays.stream(mcpTools.getToolCallbacks())
            .map(cb -> cb.getToolDefinition().name())
            .sorted()
            .toList();
    }

    /**
     * 用 MCP 工具集回答问题（协议层工具来自 MCP Server 注册，执行仍在本进程）。
     *
     * @param prompt   用户问题
     * @param provider Chat Provider id
     * @return 最终回复与工具名清单
     */
    public McpChatResult chat(String prompt, String provider) {
        List<String> toolNames = listToolNames();
        var call = registry.plainClient(provider)
            .prompt()
            .system("你是助手。需要天气、加法或 CPK 相关能力时必须调用工具，不要编造。工具经 MCP Server 注册。")
            .user(prompt)
            .tools(mcpTools)
            .call();
        return new McpChatResult(call.content(), toolNames, TokenUsageExtractor.from(call.chatResponse()));
    }

    /**
     * @param content   模型最终回复
     * @param toolNames MCP 已发现 / 已注册的工具名
     * @param usage     token 用量，网关未返回时为 {@code null}
     */
    public record McpChatResult(String content, List<String> toolNames, TokenUsage usage) {}
}
