package com.feike.ai.samples.structured;

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
     * @param text 待抽取的自然语言，不能为空
     */
    public record ExtractRequest(@NotBlank String text) {}

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
    public StructuredSampleService.Ticket extract(@RequestBody @Validated ExtractRequest request) {
        return structuredSampleService.extract(request.text());
    }
}
