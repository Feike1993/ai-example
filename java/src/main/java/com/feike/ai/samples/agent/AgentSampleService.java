package com.feike.ai.samples.agent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 样例：同一任务分别走显式 Loop 与 Spring AI 自动 tool-calling，便于对照。
 * <p>
 * 第十期：{@link #reactStream} 在弹性线程跑完整多跳，边执行边推 SSE。
 */
@Service
public class AgentSampleService {

    private final LlmProviderRegistry registry;
    private final DemoTools demoTools;
    private final AiProperties properties;
    private final String systemPrompt;
    private final JsonMapper jsonMapper;

    /**
     * @param registry     同时提供 ChatModel 与 ChatClient
     * @param demoTools    演示工具
     * @param properties   读取默认 maxSteps
     * @param promptLoader 加载 {@code prompts/agent-react.st}
     * @param jsonMapper   SSE JSON 载荷
     */
    public AgentSampleService(
        LlmProviderRegistry registry,
        DemoTools demoTools,
        AiProperties properties,
        PromptLoader promptLoader,
        JsonMapper jsonMapper
    ) throws IOException {
        this.registry = registry;
        this.demoTools = demoTools;
        this.properties = properties;
        this.systemPrompt = promptLoader.load("agent-react.st");
        this.jsonMapper = jsonMapper;
    }

    /**
     * 手写 ReAct Loop，返回逐步轨迹与累加 usage。
     *
     * @param prompt   用户任务
     * @param maxSteps 为空则用配置默认值
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 最终答案、工具步骤、是否触达步数上限、usage
     */
    public ReactAgentLoop.Trace react(String prompt, Integer maxSteps, String provider) {
        int steps = maxSteps != null ? maxSteps : properties.agent().maxSteps();
        return ReactAgentLoop.run(registry.chatModel(provider), demoTools, systemPrompt, prompt, steps);
    }

    /**
     * 可观测流式：tool_call / tool_result / 终答 / usage / done。
     *
     * @param prompt   用户任务
     * @param maxSteps 为空则用配置默认值
     * @param provider Provider id
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> reactStream(String prompt, Integer maxSteps, String provider) {
        int steps = maxSteps != null ? maxSteps : properties.agent().maxSteps();
        return Flux.<ServerSentEvent<String>>create(sink -> {
                try {
                    ReactAgentLoop.Progress progress = new ReactAgentLoop.Progress() {
                        @Override
                        public void onToolCall(int index, String assistantText, String toolName, String toolArgs) {
                            sink.next(namedEvent("tool_call", Map.of(
                                "index", index,
                                "assistantText", assistantText == null ? "" : assistantText,
                                "toolName", toolName,
                                "toolArgs", toolArgs == null ? "" : toolArgs
                            )));
                        }

                        @Override
                        public void onToolResult(int index, String toolName, String toolResult) {
                            sink.next(namedEvent("tool_result", Map.of(
                                "index", index,
                                "toolName", toolName,
                                "toolResult", toolResult == null ? "" : toolResult
                            )));
                        }
                    };
                    ReactAgentLoop.Trace trace = ReactAgentLoop.run(
                        registry.chatModel(provider),
                        demoTools,
                        systemPrompt,
                        prompt,
                        steps,
                        progress
                    );
                    // 兼容旧客户端：聚合 steps 再推终答
                    sink.next(namedEvent("steps", Map.of(
                        "steps", trace.steps(),
                        "reachedMaxSteps", trace.reachedMaxSteps()
                    )));
                    String answer = trace.finalAnswer() == null ? "" : trace.finalAnswer();
                    if (!answer.isEmpty()) {
                        sink.next(ServerSentEvent.<String>builder().data(answer).build());
                    }
                    Map<String, Object> usagePayload = new LinkedHashMap<>();
                    usagePayload.put("calls", trace.usageCalls());
                    if (trace.usage() != null) {
                        usagePayload.put("prompt", trace.usage().prompt());
                        usagePayload.put("completion", trace.usage().completion());
                        usagePayload.put("total", trace.usage().total());
                    } else {
                        usagePayload.put("prompt", null);
                        usagePayload.put("completion", null);
                        usagePayload.put("total", null);
                    }
                    sink.next(namedEvent("usage", usagePayload));
                    sink.next(namedEvent("done", Map.of("reachedMaxSteps", trace.reachedMaxSteps())));
                    sink.complete();
                } catch (Exception ex) {
                    sink.error(ex);
                }
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    private ServerSentEvent<String> namedEvent(String event, Object payload) {
        try {
            return ServerSentEvent.<String>builder()
                .event(event)
                .data(jsonMapper.writeValueAsString(payload))
                .build();
        } catch (Exception ex) {
            return ServerSentEvent.<String>builder()
                .event(event)
                .data("{}")
                .build();
        }
    }

    /**
     * 框架托管结果。
     *
     * @param content 最终文本
     * @param usage   token 用量，网关未返回时为 {@code null}
     */
    public record FrameworkResult(String content, TokenUsage usage) {}

    /**
     * 框架托管的 tool-calling 循环，对照 {@link ReactAgentLoop}。
     *
     * @param prompt   用户任务
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 最终文本与 token 用量（不含逐步轨迹）
     */
    public FrameworkResult framework(String prompt, String provider) {
        var call = registry.plainClient(provider)
            .prompt()
            .system(systemPrompt)
            .user(prompt)
            .tools(demoTools)
            .call();
        return new FrameworkResult(call.content(), TokenUsageExtractor.from(call.chatResponse()));
    }
}
