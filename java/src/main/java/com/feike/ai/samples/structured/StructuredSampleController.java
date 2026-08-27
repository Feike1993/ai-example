package com.feike.ai.samples.structured;

import com.feike.ai.core.TokenUsage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化输出样例 HTTP 入口。
 */
@Validated
@RestController
@RequestMapping("/structured")
public class StructuredSampleController {

    /**
     * @param text     待抽取的自然语言，不能为空
     * @param provider 可选 LLM Provider id，空则用默认 DeepSeek
     */
    public record ExtractRequest(@NotBlank String text, String provider) {}

    /**
     * @param ticket 结构化工单
     * @param usage  token 用量，网关未返回时为 {@code null}
     */
    public record TicketResponse(StructuredSampleService.Ticket ticket, TokenUsage usage) {}

    private final StructuredSampleService structuredSampleService;

    /**
     * @param structuredSampleService 工单抽取
     */
    public StructuredSampleController(StructuredSampleService structuredSampleService) {
        this.structuredSampleService = structuredSampleService;
    }

    /**
     * 从文本抽取工单。
     *
     * @param request 用户描述
     * @return 结构化工单
     */
    @PostMapping("/ticket")
    public TicketResponse extract(@RequestBody @Validated ExtractRequest request) {
        StructuredSampleService.ExtractResult result =
            structuredSampleService.extract(request.text(), request.provider());
        return new TicketResponse(result.ticket(), result.usage());
    }
}
