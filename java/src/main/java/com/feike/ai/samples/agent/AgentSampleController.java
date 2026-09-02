package com.feike.ai.samples.agent;

import com.feike.ai.core.TokenUsage;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
     * 显式 ReAct Loop，响应里带工具轨迹与累加 usage。
     *
     * @param request 任务与可选 maxSteps
     * @return 最终答案与步骤
     */
    @PostMapping("/react")
    public ReactAgentLoop.Trace react(@RequestBody @Validated AgentRequest request) {
        return agentSampleService.react(request.prompt(), request.maxSteps(), request.provider());
    }

    /**
     * ReAct 可观测流式：逐步 {@code tool_call}/{@code tool_result}，再终答、{@code usage}、{@code done}。
     * <p>
     * 仍推送聚合 {@code event:steps} 以兼容旧客户端；完整多跳与同步 {@code /react} 一致。
     *
     * @param prompt   用户任务
     * @param maxSteps 可选熔断步数
     * @param provider 可选 Provider id
     * @return SSE 事件流
     */
    @GetMapping(value = "/react/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reactStream(
        @RequestParam @NotBlank String prompt,
        @RequestParam(required = false) Integer maxSteps,
        @RequestParam(required = false) String provider
    ) {
        return agentSampleService.reactStream(prompt, maxSteps, provider);
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
