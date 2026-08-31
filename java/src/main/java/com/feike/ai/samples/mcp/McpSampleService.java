package com.feike.ai.samples.mcp;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 样例：进程内同时持有远端 Client 与同进程工具源，按运行时 mode 选用。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>构造期只保存 Provider 引用，不 resolve、不 initialize——配合
 *       {@code spring.ai.mcp.client.initialized=false}，主应用可在 8081 未启动时起来。</li>
 *   <li>{@link #mode} 为内存态，初值来自 {@code app.ai.mcp.mode}；{@link #setMode} 可切换且不落盘。</li>
 *   <li>remote 在首次列工具 / 聊天时懒 {@link McpSyncClient#initialize()}；连不上变接口 503，不拖垮进程。</li>
 * </ul>
 */
@Service
public class McpSampleService {

    private final LlmProviderRegistry registry;
    private final ToolCallbackProvider serverTools;
    private final ObjectProvider<SyncMcpToolCallbackProvider> clientTools;
    private final ObjectProvider<List<McpSyncClient>> syncClients;
    /** 运行时当前模式；重启后回退到配置初始值。 */
    private final AtomicReference<String> mode;

    /**
     * @param registry     LLM 工厂
     * @param properties   读取 mcp.mode 初始值
     * @param serverTools  同进程工具（{@link McpToolConfiguration}）
     * @param clientTools  远端 Client 工具（MCP Client starter，可能未启用）
     * @param syncClients  用于 remote 懒 initialize
     */
    public McpSampleService(
        LlmProviderRegistry registry,
        AiProperties properties,
        @Qualifier("mcpServerTools") ToolCallbackProvider serverTools,
        ObjectProvider<SyncMcpToolCallbackProvider> clientTools,
        ObjectProvider<List<McpSyncClient>> syncClients
    ) {
        this.registry = registry;
        this.serverTools = serverTools;
        this.clientTools = clientTools;
        this.syncClients = syncClients;
        this.mode = new AtomicReference<>(properties.mcp().mode());
    }

    /**
     * @return 当前模式 remote / inprocess
     */
    public String mode() {
        return mode.get();
    }

    /**
     * 切换全局 MCP 模式（内存）；非法值 400。
     * <p>
     * 只改引用、不在此处连远端——列工具失败时 mode 已生效，前端仍可展示当前选项。
     *
     * @param next {@code remote} 或 {@code inprocess}
     * @return 规范化后的模式
     */
    public String setMode(String next) {
        if (next == null || next.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode 不能为空");
        }
        String normalized = next.trim().toLowerCase();
        if (!normalized.equals("remote") && !normalized.equals("inprocess")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "mode 仅支持 remote 或 inprocess，实际: " + next
            );
        }
        mode.set(normalized);
        return normalized;
    }

    /**
     * 按当前 mode 解析工具 Provider；remote 时先懒 initialize。
     *
     * @return 当前模式对应的 ToolCallbackProvider
     */
    public ToolCallbackProvider resolveTools() {
        if ("remote".equals(mode.get())) {
            return resolveRemoteTools();
        }
        return serverTools;
    }

    /**
     * 列出当前工具名。
     *
     * @return 工具名列表
     */
    public List<String> listToolNames() {
        try {
            return Arrays.stream(resolveTools().getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "无法列出 MCP 工具（remote 时请先启动 mcp-server:8081）: " + ex.getMessage()
            );
        }
    }

    /**
     * 用当前模式的工具集回答问题。
     *
     * @param prompt   用户问题
     * @param provider Chat Provider id
     * @return 最终回复、工具名与模式
     */
    public McpChatResult chat(String prompt, String provider) {
        ToolCallbackProvider tools = resolveTools();
        List<String> toolNames = Arrays.stream(tools.getToolCallbacks())
            .map(cb -> cb.getToolDefinition().name())
            .sorted()
            .toList();
        var call = registry.plainClient(provider)
            .prompt()
            .system("你是助手。需要天气或加法时必须调用工具，不要编造。工具经 MCP 提供。")
            .user(prompt)
            .tools(tools)
            .call();
        return new McpChatResult(
            call.content(),
            toolNames,
            TokenUsageExtractor.from(call.chatResponse()),
            mode.get()
        );
    }

    private ToolCallbackProvider resolveRemoteTools() {
        SyncMcpToolCallbackProvider remote;
        try {
            remote = clientTools.getIfAvailable();
        } catch (Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MCP Client 不可用: " + ex.getMessage()
            );
        }
        if (remote == null) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "MCP mode=remote 但未找到 SyncMcpToolCallbackProvider；请确认 spring.ai.mcp.client.enabled=true"
            );
        }
        // initialized=false 时 Bean 已在，此处才真正握手；失败只影响本请求
        ensureRemoteInitialized();
        return remote;
    }

    /**
     * 确保远端 Client 初始化；失败只影响本请求。
     */
    private void ensureRemoteInitialized() {
        // 与 McpToolCallbackAutoConfiguration 一致：stream 展平 List Bean
        List<McpSyncClient> clients = syncClients.stream().flatMap(List::stream).toList();
        if (clients.isEmpty()) {
            return;
        }
        try {
            for (McpSyncClient client : clients) {
                if (!client.isInitialized()) {
                    client.initialize();
                }
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "无法连接 MCP Server（请先启动 mcp-server:8081）: " + ex.getMessage()
            );
        }
    }

    /**
     * @param content   模型最终回复
     * @param toolNames 工具名
     * @param usage     token 用量
     * @param mode      remote / inprocess
     */
    public record McpChatResult(String content, List<String> toolNames, TokenUsage usage, String mode) {}
}
