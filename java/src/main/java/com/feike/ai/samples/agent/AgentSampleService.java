package com.feike.ai.samples.agent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;

/**
 * Agent 样例：同一任务分别走显式 Loop 与 Spring AI 自动 tool-calling，便于对照。
 */
@Service
public class AgentSampleService {

    private final LlmProviderRegistry registry;
    private final DemoTools demoTools;
    private final AiProperties properties;
    private final String systemPrompt;

    /**
     * @param registry     同时提供 ChatModel 与 ChatClient
     * @param demoTools    演示工具
     * @param properties   读取默认 maxSteps
     * @param promptLoader 加载 {@code prompts/agent-react.st}
     */
    public AgentSampleService(
        LlmProviderRegistry registry,
        DemoTools demoTools,
        AiProperties properties,
        PromptLoader promptLoader
    ) throws IOException {
        this.registry = registry;
        this.demoTools = demoTools;
        this.properties = properties;
        this.systemPrompt = promptLoader.load("agent-react.st");
    }

    /**
     * 手写 ReAct Loop，返回逐步轨迹。
     *
     * @param prompt   用户任务
     * @param maxSteps 为空则用配置默认值
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 最终答案、工具步骤、是否触达步数上限
     */
    public ReactAgentLoop.Trace react(String prompt, Integer maxSteps, String provider) {
        int steps = maxSteps != null ? maxSteps : properties.agent().maxSteps();
        return ReactAgentLoop.run(registry.chatModel(provider), demoTools, systemPrompt, prompt, steps);
    }

    /**
     * ReAct 流式准备：工具轮在弹性线程同步执行，避免阻塞事件循环。
     * <p>
     * 用 {@link Mono} 而不是直接调用：准备结果只有一份（0～1 个元素），
     * {@code fromCallable} 在订阅时才执行阻塞的 {@code prepareStream}，
     * {@code subscribeOn(boundedElastic)} 把它放到弹性线程池。
     *
     * @param prompt   用户任务
     * @param maxSteps 为空则用配置默认值
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 含 steps 与终答路径的准备结果
     */
    public Mono<ReactAgentLoop.StreamPrep> prepareReactStream(String prompt, Integer maxSteps, String provider) {
        int steps = maxSteps != null ? maxSteps : properties.agent().maxSteps();
        return Mono.fromCallable(() ->
                ReactAgentLoop.prepareStream(
                    registry.chatModel(provider), demoTools, systemPrompt, prompt, steps))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在已有消息（含 tool 观察）上不挂 tools 地流式生成终答。
     *
     * @param messages 完整消息列表
     * @param provider Provider id
     * @return 增量文本
     */
    public Flux<String> streamFinalAnswer(List<Message> messages, String provider) {
        return registry.plainClient(provider)
            .prompt()
            .messages(messages)
            .stream()
            .content();
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
