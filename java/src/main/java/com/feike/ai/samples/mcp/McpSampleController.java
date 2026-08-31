package com.feike.ai.samples.mcp;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
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

    /**
     * @param mode {@code remote} 或 {@code inprocess}
     */
    public record McpModeRequest(@NotBlank String mode) {}

    private final McpSampleService mcpSampleService;

    /**
     * @param mcpSampleService MCP 聊天与工具发现
     */
    public McpSampleController(McpSampleService mcpSampleService) {
        this.mcpSampleService = mcpSampleService;
    }

    /**
     * 列出当前模式注册的工具名。
     * <p>
     * remote 连不上时仍返回 200 + 当前 {@code mode} 与空列表，并带 {@code error}，
     * 便于面板在未起 mcp-server 时仍能展示模式切换器。
     *
     * @return {@code toolNames}、{@code mode}，失败时另有 {@code error}
     */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mcpSampleService.mode());
        try {
            body.put("toolNames", mcpSampleService.listToolNames());
        } catch (ResponseStatusException ex) {
            body.put("toolNames", List.of());
            body.put("error", ex.getReason() != null ? ex.getReason() : ex.getMessage());
        }
        return body;
    }

    /**
     * 切换全局 MCP 模式（内存，不持久化）。
     * <p>
     * 先 {@link McpSampleService#setMode} 再列工具：即使列工具失败，mode 已生效；
     * 此时返回 503 且 body 仍含 {@code mode}，前端可同步 SegmentedControl 并清空工具列表。
     *
     * @param request 目标模式
     * @return 新 mode 与 toolNames；工具不可用时 503 + error
     */
    @PutMapping("/mode")
    public ResponseEntity<Map<String, Object>> setMode(@RequestBody @Validated McpModeRequest request) {
        String mode = mcpSampleService.setMode(request.mode());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        try {
            body.put("toolNames", mcpSampleService.listToolNames());
            return ResponseEntity.ok(body);
        } catch (ResponseStatusException ex) {
            body.put("toolNames", List.of());
            body.put(
                "error",
                "模式已切换为 " + mode + "，但无法列出工具: "
                    + (ex.getReason() != null ? ex.getReason() : ex.getMessage())
            );
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /**
     * 经当前模式的 MCP 工具集回答问题。
     *
     * @param request 提示词与 Provider
     * @return 回复 + 工具名列表
     */
    @PostMapping("/chat")
    public McpSampleService.McpChatResult chat(@RequestBody @Validated McpChatRequest request) {
        try {
            return mcpSampleService.chat(request.prompt(), request.provider());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MCP 调用失败（remote 时请先启动 mcp-server:8081）: " + ex.getMessage()
            );
        }
    }
}
