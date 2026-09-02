package com.feike.ai.samples.mcp;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.samples.tools.CpkTools;
import com.feike.ai.samples.tools.DemoTools;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时 mode 切换与 remote 不可用时的 503 行为。
 */
@DisplayName("McpSampleService mode")
class McpSampleServiceTest {

    @Test
    void setModeShouldSwitchAndListInprocessTools() {
        ToolCallbackProvider serverTools = new McpToolConfiguration()
            .mcpServerTools(new DemoTools(), new CpkTools());
        McpSampleService service = newService("remote", serverTools, null, null);

        assertEquals("remote", service.mode());
        assertEquals("inprocess", service.setMode("inprocess"));
        assertEquals("inprocess", service.mode());

        List<String> names = service.listToolNames();
        assertTrue(names.stream().anyMatch(n -> n.equals("getWeather") || n.toLowerCase().contains("weather")),
            "应包含天气工具: " + names);
        assertTrue(names.stream().anyMatch(n -> n.equals("add") || n.toLowerCase().contains("add")),
            "应包含加法工具: " + names);
    }

    @Test
    void remoteWithoutClientBeanShouldReturn503() {
        ToolCallbackProvider serverTools = new McpToolConfiguration()
            .mcpServerTools(new DemoTools(), new CpkTools());
        McpSampleService service = newService("inprocess", serverTools, null, null);
        service.setMode("remote");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, service::listToolNames);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    void invalidModeShouldReturn400() {
        ToolCallbackProvider serverTools = new McpToolConfiguration()
            .mcpServerTools(new DemoTools(), new CpkTools());
        McpSampleService service = newService("inprocess", serverTools, null, null);

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> service.setMode("stdio")
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("inprocess", service.mode());
    }

    @SuppressWarnings("unchecked")
    private static McpSampleService newService(
        String initialMode,
        ToolCallbackProvider serverTools,
        SyncMcpToolCallbackProvider remote,
        List<McpSyncClient> clients
    ) {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        AiProperties properties = new AiProperties(
            "deepseek",
            0.2,
            null,
            null,
            null,
            "dashscope",
            null,
            null,
            null,
            null,
            null,
            new AiProperties.Mcp(initialMode, "dev-mcp-token")
        );

        ObjectProvider<SyncMcpToolCallbackProvider> clientTools = mock(ObjectProvider.class);
        when(clientTools.getIfAvailable()).thenReturn(remote);

        ObjectProvider<List<McpSyncClient>> syncClients = mock(ObjectProvider.class);
        when(syncClients.getIfAvailable()).thenReturn(clients);

        return new McpSampleService(registry, properties, serverTools, clientTools, syncClients);
    }
}
