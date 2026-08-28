package com.feike.ai.samples.eval;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 评测 HTTP：一键跑 golden suite。
 */
@RestController
@RequestMapping("/eval")
public class EvalSampleController {

    /**
     * @param provider 可选 Chat Provider
     */
    public record EvalRunRequest(String provider) {}

    private final EvalSampleService evalSampleService;

    public EvalSampleController(EvalSampleService evalSampleService) {
        this.evalSampleService = evalSampleService;
    }

    /**
     * 跑 classpath golden 全集并返回通过率报告。
     */
    @PostMapping("/run")
    public EvalSampleService.EvalRunResult run(@RequestBody(required = false) EvalRunRequest request) {
        String provider = request == null ? null : request.provider();
        return evalSampleService.runAll(provider);
    }
}
