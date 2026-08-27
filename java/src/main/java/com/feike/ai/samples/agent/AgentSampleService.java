package com.feike.ai.samples.agent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.TokenUsage;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.stereotype.Service;

import java.io.IOException;

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
