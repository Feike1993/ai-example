package com.feike.ai.samples.tools;

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
@RequestMapping("/api/samples/tools")
public class ToolSampleController {

    /**
     * @param prompt 用户问题，不能为空
     */
    public record ToolChatRequest(@NotBlank String prompt) {}

    /**
     * @param content 结合工具结果后的回复
     */
    public record ToolChatResponse(String content) {}

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
        return new ToolChatResponse(toolSampleService.chatWithTools(request.prompt()));
    }
}
