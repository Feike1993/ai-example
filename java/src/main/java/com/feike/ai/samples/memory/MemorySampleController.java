package com.feike.ai.samples.memory;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 长期记忆样例 HTTP：remember / recall / chat / clear。
 */
@Validated
@RestController
@RequestMapping("/memory")
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemorySampleController {

    public record RememberRequest(@NotBlank String text, String userId, String sessionId) {}

    public record RecallRequest(@NotBlank String query, String userId, Integer topK) {}

    public record ChatRequest(@NotBlank String prompt, String userId, String provider, Integer topK) {}

    private final MemorySampleService memorySampleService;

    public MemorySampleController(MemorySampleService memorySampleService) {
        this.memorySampleService = memorySampleService;
    }

    // 记忆
    @PostMapping("/remember")
    public MemorySampleService.RememberResult remember(@RequestBody @Validated RememberRequest request) {
        try {
            return memorySampleService.remember(request.text(), request.userId(), request.sessionId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    // 召回
    @PostMapping("/recall")
    public MemorySampleService.RecallResult recall(@RequestBody @Validated RecallRequest request) {
        return memorySampleService.recall(request.query(), request.userId(), request.topK());
    }

    // 聊天
    @PostMapping("/chat")
    public MemorySampleService.MemoryChatResult chat(@RequestBody @Validated ChatRequest request) {
        return memorySampleService.chat(
            request.prompt(),
            request.userId(),
            request.provider(),
            request.topK()
        );
    }

    // 清空
    @DeleteMapping
    public MemorySampleService.ClearResult clear(@RequestParam(required = false) String userId) {
        return memorySampleService.clear(userId);
    }
}
