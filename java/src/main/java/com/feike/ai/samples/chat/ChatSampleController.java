package com.feike.ai.samples.chat;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Chat 样例 HTTP 入口。
 */
@Validated
@RestController
@RequestMapping("/chat")
public class ChatSampleController {

    /**
     * @param prompt      用户问题，不能为空
     * @param temperature 可选采样温度
     */
    public record ChatRequest(@NotBlank String prompt, Double temperature) {}

    /**
     * @param content 模型完整回复
     */
    public record ChatResponse(String content) {}

    private final ChatSampleService chatSampleService;

    /**
     * @param chatSampleService 同步 / 流式补全
     */
    public ChatSampleController(ChatSampleService chatSampleService) {
        this.chatSampleService = chatSampleService;
    }

    /**
     * 同步聊天。
     *
     * @param request 提示词与可选温度
     * @return 完整回复
     */
    @PostMapping
    public ChatResponse chat(@RequestBody @Validated ChatRequest request) {
        return new ChatResponse(chatSampleService.chat(request.prompt(), request.temperature()));
    }

    /**
     * SSE 流式聊天。
     *
     * @param prompt 用户问题
     * @return {@code text/event-stream} 增量
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam @NotBlank String prompt) {
        return chatSampleService.stream(prompt);
    }
}
