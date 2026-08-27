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
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final JsonMapper jsonMapper;

    /**
     * @param agentSampleService 显式 / 框架两种运行方式
     * @param jsonMapper         序列化 steps 首包（Spring Boot 4 / Jackson 3）
     */
    public AgentSampleController(AgentSampleService agentSampleService, JsonMapper jsonMapper) {
        this.agentSampleService = agentSampleService;
        this.jsonMapper = jsonMapper;
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
     * ReAct 最小流式：工具轮同步完成后推送 {@code event:steps}，再 SSE 终答增量。
     * <p>
     * 刻意不做逐步 tool_call 实时 SSE。
     *
     * @param prompt   用户任务
     * @param maxSteps 可选熔断步数
     * @param provider 可选 Provider id
     * @return SSE：首包 steps，后续为 finalAnswer token
     */
    @GetMapping(value = "/react/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reactStream(
        @RequestParam @NotBlank String prompt,
        @RequestParam(required = false) Integer maxSteps,
        @RequestParam(required = false) String provider
    ) {
        return agentSampleService.prepareReactStream(prompt, maxSteps, provider)
                // 准备工作完成后，开始生成步骤
            .flatMapMany(prep -> {
                // 首包 steps
                ServerSentEvent<String> stepsEvent = ServerSentEvent.<String>builder()
                    .event("steps")
                    .data(stepsPayload(prep))
                    .build();
                Flux<ServerSentEvent<String>> answerEvents;
                if (prep.messages() != null) {
                    answerEvents = agentSampleService
                        .streamFinalAnswer(prep.messages(), provider)
                        .map(chunk -> ServerSentEvent.<String>builder().data(chunk).build());
                } else {
                    String answer = prep.finalAnswer() == null ? "" : prep.finalAnswer();
                    answerEvents = Flux.just(ServerSentEvent.<String>builder().data(answer).build());
                }
                // 首包 steps 后接最终答案
                return Flux.concat(Flux.just(stepsEvent), answerEvents);
            });
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

    private String stepsPayload(ReactAgentLoop.StreamPrep prep) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("steps", prep.steps());
            payload.put("reachedMaxSteps", prep.reachedMaxSteps());
            return jsonMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"steps\":[],\"reachedMaxSteps\":false}";
        }
    }
}
