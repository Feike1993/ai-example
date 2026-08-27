package com.feike.ai.samples.agent;

import com.feike.ai.core.TokenUsage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Loop 样例 HTTP 入口：显式 ReAct 与框架托管两条路径。
 */
@Validated
@RestController
@RequestMapping("/agent")
public class AgentSampleController {

    /**
     * @param prompt   用户任务，不能为空
     * @param maxSteps 可选熔断步数；仅显式 Loop 使用
     * @param provider 可选 LLM Provider id，空则用默认 DeepSeek
     */
    public record AgentRequest(@NotBlank String prompt, Integer maxSteps, String provider) {}

    /**
     * @param content 框架托管循环的最终回复
     * @param usage   token 用量，网关未返回时为 {@code null}
     */
    public record FrameworkResponse(String content, TokenUsage usage) {}

    private final AgentSampleService agentSampleService;

    /**
     * @param agentSampleService 显式 / 框架两种运行方式
     */
    public AgentSampleController(AgentSampleService agentSampleService) {
        this.agentSampleService = agentSampleService;
    }

    /**
     * 显式 ReAct Loop，响应里带工具轨迹。
     *
     * @param request 任务与可选 maxSteps
     * @return 最终答案与步骤
     */
    @PostMapping("/react")
    public ReactAgentLoop.Trace react(@RequestBody @Validated AgentRequest request) {
        return agentSampleService.react(request.prompt(), request.maxSteps(), request.provider());
    }

    /**
     * Spring AI 自动执行 tool_calls，只返回最终文本。
     *
     * @param request 用户任务
     * @return 最终回复
     */
    @PostMapping("/framework")
    public FrameworkResponse framework(@RequestBody @Validated AgentRequest request) {
        AgentSampleService.FrameworkResult result =
            agentSampleService.framework(request.prompt(), request.provider());
        return new FrameworkResponse(result.content(), result.usage());
    }
}
