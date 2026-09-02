package com.feike.ai.samples.guardrail;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 输出护栏 HTTP：{@code POST /guardrail/chat}。
 */
@Validated
@RestController
@RequestMapping("/guardrail")
public class GuardrailSampleController {

    /**
     * @param prompt            用户输入
     * @param provider          可选 Provider
     * @param requireStructured 是否强制 SafeEnvelope
     */
    public record GuardrailRequest(
        @NotBlank String prompt,
        String provider,
        Boolean requireStructured
    ) {}

    /**
     * @param blocked    是否拦截
     * @param blockStage 拦截阶段
     * @param answer     展示文本
     * @param checks     逐步检查
     * @param usage      token 用量
     */
    public record GuardrailResponse(
        boolean blocked,
        String blockStage,
        String answer,
        List<GuardrailSampleService.Check> checks,
        com.feike.ai.core.TokenUsage usage
    ) {}

    private final GuardrailSampleService guardrailSampleService;

    /**
     * @param guardrailSampleService 护栏管道
     */
    public GuardrailSampleController(GuardrailSampleService guardrailSampleService) {
        this.guardrailSampleService = guardrailSampleService;
    }

    /**
     * 同步护栏聊天。
     *
     * @param request 提示与开关
     * @return 含 checks 的结果
     */
    @PostMapping("/chat")
    public GuardrailResponse chat(@RequestBody @Validated GuardrailRequest request) {
        boolean requireStructured = Boolean.TRUE.equals(request.requireStructured());
        GuardrailSampleService.GuardrailResult result =
            guardrailSampleService.chat(request.prompt(), request.provider(), requireStructured);
        return new GuardrailResponse(
            result.blocked(),
            result.blockStage(),
            result.answer(),
            result.checks(),
            result.usage()
        );
    }
}
