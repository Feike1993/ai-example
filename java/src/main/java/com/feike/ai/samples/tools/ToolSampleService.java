package com.feike.ai.samples.tools;

import com.feike.ai.core.LlmProviderRegistry;
import org.springframework.stereotype.Service;

/**
 * Function Calling 样例：按请求挂载 {@link DemoTools}，不把工具绑到全局 ChatClient。
 */
@Service
public class ToolSampleService {

    private final LlmProviderRegistry registry;
    private final DemoTools demoTools;
    private final CpkTools cpkTools;

    /**
     * @param registry  按请求选择 Provider
     * @param demoTools 本请求可用的工具集
     */
    public ToolSampleService(LlmProviderRegistry registry, DemoTools demoTools, CpkTools cpkTools) {
        this.registry = registry;
        this.demoTools = demoTools;
        this.cpkTools = cpkTools;
    }

    /**
     * 让模型在需要时调用天气 / 加法工具；由 Spring AI 自动执行 tool_calls。
     *
     * @param prompt   用户问题
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 结合工具结果后的最终回复
     */
    public String chatWithTools(String prompt, String provider) {
        return registry.plainClient(provider)
            .prompt()
            .system("你是助手。需要天气或加法时必须调用工具，不要编造。")
            .user(prompt)
            .tools(demoTools,cpkTools)
            .call()
            .content();
    }
}
