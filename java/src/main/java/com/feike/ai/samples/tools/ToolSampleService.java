package com.feike.ai.samples.tools;

import com.feike.ai.core.LlmClientFactory;
import org.springframework.stereotype.Service;

/**
 * Function Calling 样例：按请求挂载 {@link DemoTools}，不把工具绑到全局 ChatClient。
 */
@Service
public class ToolSampleService {

    private final LlmClientFactory factory;
    private final DemoTools demoTools;

    /**
     * @param factory   提供 plain ChatClient
     * @param demoTools 本请求可用的工具集
     */
    public ToolSampleService(LlmClientFactory factory, DemoTools demoTools) {
        this.factory = factory;
        this.demoTools = demoTools;
    }

    /**
     * 让模型在需要时调用天气 / 加法工具；由 Spring AI 自动执行 tool_calls。
     *
     * @param prompt 用户问题
     * @return 结合工具结果后的最终回复
     */
    public String chatWithTools(String prompt) {
        return factory.plainClient()
            .prompt()
            .system("你是助手。需要天气或加法时必须调用工具，不要编造。")
            .user(prompt)
            .tools(demoTools)
            .call()
            .content();
    }
}
