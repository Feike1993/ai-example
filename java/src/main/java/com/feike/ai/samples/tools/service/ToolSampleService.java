package com.feike.ai.samples.tools.service;

import com.feike.ai.core.model.TokenUsageDTO;

/** ToolSampleService 业务门面。 */
public interface ToolSampleService {

    record ToolChatResult(String content, TokenUsageDTO usage) {}
    ToolChatResult chatWithTools(String prompt, String provider);
}
