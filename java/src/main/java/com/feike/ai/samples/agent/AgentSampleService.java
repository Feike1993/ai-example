package com.feike.ai.samples.agent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmClientFactory;
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

    private final LlmClientFactory factory;
    private final DemoTools demoTools;
    private final AiProperties properties;

    /**
     * @param factory    同时提供 ChatModel 与 ChatClient
     * @param demoTools  演示工具
     * @param properties 读取默认 maxSteps
     */
    public AgentSampleService(LlmClientFactory factory, DemoTools demoTools, AiProperties properties) {
        this.factory = factory;
        this.demoTools = demoTools;
        this.properties = properties;
    }

    /**
     * 手写 ReAct Loop，返回逐步轨迹。
     *
     * @param prompt   用户任务
     * @param maxSteps 为空则用配置默认值
     * @return 最终答案、工具步骤、是否触达步数上限
     */
    public ReactAgentLoop.Trace react(String prompt, Integer maxSteps) {
        int steps = maxSteps != null ? maxSteps : properties.agent().maxSteps();
        return ReactAgentLoop.run(factory.chatModel(), demoTools, SYSTEM, prompt, steps);
    }

    /**
     * 框架托管的 tool-calling 循环，对照 {@link ReactAgentLoop}。
     *
     * @param prompt 用户任务
     * @return 仅最终文本，不含逐步轨迹
     */
    public String framework(String prompt) {
        return factory.plainClient()
            .prompt()
            .system(SYSTEM)
            .user(prompt)
            .tools(demoTools)
            .call()
            .content();
    }
}
