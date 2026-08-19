package com.feike.ai.samples.agent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.stereotype.Service;

/**
 * Agent 样例：同一任务分别走显式 Loop 与 Spring AI 自动 tool-calling，便于对照。
 */
@Service
public class AgentSampleService {

    private static final String SYSTEM = """
        你是会使用工具的助手。需要天气或加法时调用工具，不要编造数字。
        得到工具结果后，用中文给出最终答案。
        """;

    private final LlmProviderRegistry registry;
    private final DemoTools demoTools;
    private final AiProperties properties;

    /**
     * @param registry   同时提供 ChatModel 与 ChatClient
     * @param demoTools  演示工具
     * @param properties 读取默认 maxSteps
     */
    public AgentSampleService(LlmProviderRegistry registry, DemoTools demoTools, AiProperties properties) {
        this.registry = registry;
        this.demoTools = demoTools;
        this.properties = properties;
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
        return ReactAgentLoop.run(registry.chatModel(provider), demoTools, SYSTEM, prompt, steps);
    }

    /**
     * 框架托管的 tool-calling 循环，对照 {@link ReactAgentLoop}。
     *
     * @param prompt   用户任务
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 仅最终文本，不含逐步轨迹
     */
    public String framework(String prompt, String provider) {
        return registry.plainClient(provider)
            .prompt()
            .system(SYSTEM)
            .user(prompt)
            .tools(demoTools)
            .call()
            .content();
    }
}
