package com.feike.ai.samples.chat.controller;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.samples.chat.model.ChatRequestDTO;
import com.feike.ai.samples.chat.model.ChatVO;
import com.feike.ai.samples.chat.service.ChatSampleService;
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
    public ChatVO chat(@RequestBody @Validated ChatRequestDTO request) {
        var result = chatSampleService.chat(request.prompt(), request.temperature(), request.provider());
        return new ChatVO(result.content(), result.usage());
    }

    /**
     * SSE 流式聊天。
     *
     * @param prompt   用户问题
     * @param provider 可选 LLM Provider id
     * @return {@code text/event-stream} 增量
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
        @RequestParam @NotBlank String prompt,
        @RequestParam(required = false) String provider
    ) {
        return chatSampleService.stream(prompt, provider);
    }
}
