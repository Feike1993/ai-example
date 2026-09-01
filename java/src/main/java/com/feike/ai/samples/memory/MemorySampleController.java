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

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆样例 HTTP：remember / recall / chat / extract / clear。
 */
@Validated
@RestController
@RequestMapping("/memory")
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemorySampleController {

    public record RememberRequest(@NotBlank String text, String userId, String sessionId) {}

    public record RecallRequest(@NotBlank String query, String userId, Integer topK) {}

    public record ChatRequest(@NotBlank String prompt, String userId, String provider, Integer topK) {}

    /**
     * @param messages   显式对话；与 turns / sessionId 三选一优先 messages
     * @param turns      user/assistant 对，转成 messages
     * @param sessionId  仅传 id 时从 ChatSessionStore 拉快照
     */
    public record ExtractRequest(
        String userId,
        String sessionId,
        String provider,
        List<MemorySampleService.DialogueMessage> messages,
        List<Turn> turns
    ) {}

    public record Turn(String user, String assistant) {}

    private final MemorySampleService memorySampleService;

    public MemorySampleController(MemorySampleService memorySampleService) {
        this.memorySampleService = memorySampleService;
    }

    @PostMapping("/remember")
    public MemorySampleService.RememberResult remember(@RequestBody @Validated RememberRequest request) {
        try {
            return memorySampleService.remember(request.text(), request.userId(), request.sessionId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/recall")
    public MemorySampleService.RecallResult recall(@RequestBody @Validated RecallRequest request) {
        return memorySampleService.recall(request.query(), request.userId(), request.topK());
    }

    @PostMapping("/chat")
    public MemorySampleService.MemoryChatResult chat(@RequestBody @Validated ChatRequest request) {
        return memorySampleService.chat(
            request.prompt(),
            request.userId(),
            request.provider(),
            request.topK()
        );
    }

    @PostMapping("/extract")
    public MemorySampleService.ExtractResult extract(@RequestBody ExtractRequest request) {
        try {
            List<MemorySampleService.DialogueMessage> messages = resolveMessages(request);
            if (!messages.isEmpty()) {
                return memorySampleService.extract(
                    messages,
                    request == null ? null : request.userId(),
                    request == null ? null : request.sessionId(),
                    request == null ? null : request.provider()
                );
            }
            if (request != null && request.sessionId() != null && !request.sessionId().isBlank()) {
                return memorySampleService.extractFromSession(
                    request.sessionId(),
                    request.userId(),
                    request.provider()
                );
            }
            throw new IllegalArgumentException("请提供 messages、turns 或可加载的 sessionId");
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("会话不存在") || msg.contains("无可抽取")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    @DeleteMapping
    public MemorySampleService.ClearResult clear(@RequestParam(required = false) String userId) {
        return memorySampleService.clear(userId);
    }

    private static List<MemorySampleService.DialogueMessage> resolveMessages(ExtractRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.messages() != null && !request.messages().isEmpty()) {
            return request.messages();
        }
        if (request.turns() == null || request.turns().isEmpty()) {
            return List.of();
        }
        List<MemorySampleService.DialogueMessage> out = new ArrayList<>();
        for (Turn turn : request.turns()) {
            if (turn == null) {
                continue;
            }
            if (turn.user() != null && !turn.user().isBlank()) {
                out.add(new MemorySampleService.DialogueMessage("user", turn.user()));
            }
            if (turn.assistant() != null && !turn.assistant().isBlank()) {
                out.add(new MemorySampleService.DialogueMessage("assistant", turn.assistant()));
            }
        }
        return out;
    }
}
