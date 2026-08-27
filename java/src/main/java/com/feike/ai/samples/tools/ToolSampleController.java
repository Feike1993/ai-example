package com.feike.ai.samples.tools;

import com.feike.ai.core.TokenUsage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool Calling 样例 HTTP 入口。
 */
@Validated
@RestController
@RequestMapping("/tools")
public class ToolSampleController {

    /**
     * @param prompt   用户问题，不能为空
     * @param provider 可选 LLM Provider id，空则用默认 DeepSeek
     */
    public record ToolChatRequest(@NotBlank String prompt, String provider) {}

    /**
     * @param content 结合工具结果后的回复
     * @param usage   token 用量，网关未返回时为 {@code null}
     */
    public record ToolChatResponse(String content, TokenUsage usage) {}

    private final ToolSampleService toolSampleService;

    /**
     * @param toolSampleService 带工具的聊天
     */
    public ToolSampleController(ToolSampleService toolSampleService) {
        this.toolSampleService = toolSampleService;
    }

    /**
     * 使用演示工具回答问题。
     *
     * @param request 用户问题
     * @return 最终回复
     */
    @PostMapping
    public ToolChatResponse chat(@RequestBody @Validated ToolChatRequest request) {
        ToolSampleService.ToolChatResult result =
            toolSampleService.chatWithTools(request.prompt(), request.provider());
        return new ToolChatResponse(result.content(), result.usage());
    }
}
