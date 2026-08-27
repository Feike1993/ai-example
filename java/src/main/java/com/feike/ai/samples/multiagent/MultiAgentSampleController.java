package com.feike.ai.samples.multiagent;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多 Agent 样例 HTTP。
 */
@Validated
@RestController
@RequestMapping("/multiagent")
public class MultiAgentSampleController {

    /**
     * @param prompt   用户任务
     * @param provider 可选 Chat Provider
     */
    public record MultiAgentRequest(@NotBlank String prompt, String provider) {}

    private final MultiAgentSampleService multiAgentSampleService;

    /**
     * @param multiAgentSampleService 协作运行
     */
    public MultiAgentSampleController(MultiAgentSampleService multiAgentSampleService) {
        this.multiAgentSampleService = multiAgentSampleService;
    }

    /**
     * 运行 Orchestrator–Subagent 一轮。
     *
     * @param request 任务
     * @return 最终答复与轨迹
     */
    @PostMapping("/run")
    public MultiAgentSampleService.MultiAgentResult run(@RequestBody @Validated MultiAgentRequest request) {
        return multiAgentSampleService.run(request.prompt(), request.provider());
    }
}
