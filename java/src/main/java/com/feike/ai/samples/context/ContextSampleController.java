package com.feike.ai.samples.context;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 上下文工程样例 HTTP。
 */
@Validated
@RestController
@RequestMapping("/context")
public class ContextSampleController {

    /**
     * @param prompt    本轮用户输入
     * @param sessionId 可选；空则新建
     * @param provider  可选 Chat Provider
     * @param strategy  trim 或 summarize
     */
    public record ContextChatRequest(
        @NotBlank String prompt,
        String sessionId,
        String provider,
        String strategy
    ) {}

    private final ContextSampleService contextSampleService;

    /**
     * @param contextSampleService 会话聊天
     */
    public ContextSampleController(ContextSampleService contextSampleService) {
        this.contextSampleService = contextSampleService;
    }

    /**
     * 带记忆的一轮聊天。
     *
     * @param request 请求体
     * @return 回复与预算元数据
     */
    @PostMapping("/chat")
    public ContextSampleService.ContextChatResult chat(@RequestBody @Validated ContextChatRequest request) {
        return contextSampleService.chat(
            request.sessionId(),
            request.prompt(),
            request.provider(),
            ContextStrategy.from(request.strategy())
        );
    }

    /**
     * 调试：查看会话原始消息。
     *
     * @param id sessionId
     * @return messages
     */
    @GetMapping("/session/{id}")
    public Map<String, Object> session(@PathVariable("id") String id) {
        List<InMemoryChatSessionStore.MessageView> messages = contextSampleService.session(id);
        return Map.of("sessionId", id, "messages", messages);
    }

    /**
     * 清空会话。
     *
     * @param id sessionId
     * @return ok
     */
    @DeleteMapping("/session/{id}")
    public Map<String, Object> clear(@PathVariable("id") String id) {
        boolean removed = contextSampleService.clear(id);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + id);
        }
        return Map.of("sessionId", id, "cleared", true);
    }
}
