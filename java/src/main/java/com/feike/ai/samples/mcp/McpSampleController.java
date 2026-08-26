package com.feike.ai.samples.mcp;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP 样例 HTTP 入口。
 */
@Validated
@RestController
@RequestMapping("/mcp")
public class McpSampleController {

    /**
     * @param prompt   用户问题
     * @param provider 可选 Chat Provider
     */
    public record McpChatRequest(@NotBlank String prompt, String provider) {}

    private final McpSampleService mcpSampleService;

    /**
     * @param mcpSampleService MCP 聊天与工具发现
     */
    public McpSampleController(McpSampleService mcpSampleService) {
        this.mcpSampleService = mcpSampleService;
    }

    /**
     * 列出 MCP Server 注册的工具名。
     *
     * @return {@code toolNames}
     */
    @GetMapping("/tools")
    public Map<String, List<String>> tools() {
        return Map.of("toolNames", mcpSampleService.listToolNames());
    }

    /**
     * 经 MCP 注册工具回答问题。
     *
     * @param request 提示词与 Provider
     * @return 回复 + 工具名列表
     */
    @PostMapping("/chat")
    public McpSampleService.McpChatResult chat(@RequestBody @Validated McpChatRequest request) {
        return mcpSampleService.chat(request.prompt(), request.provider());
    }
}
