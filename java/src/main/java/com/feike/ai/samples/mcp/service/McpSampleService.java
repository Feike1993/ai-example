package com.feike.ai.samples.mcp.service;

import com.feike.ai.core.model.TokenUsageDTO;
import org.springframework.ai.tool.ToolCallbackProvider;
import java.util.List;

/** McpSampleService 业务门面。 */
public interface McpSampleService {

    record McpChatResult(String content, List<String> toolNames, TokenUsageDTO usage, String mode) {}
    String mode();
    String setMode(String next);
    ToolCallbackProvider resolveTools();
    List<String> listToolNames();
    McpChatResult chat(String prompt, String provider);
}
