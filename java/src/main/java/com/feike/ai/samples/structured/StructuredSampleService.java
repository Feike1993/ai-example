package com.feike.ai.samples.structured;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.TokenUsage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 从自然语言描述抽出工单 JSON，演示结构化输出如何接到业务对象。
 */
@Service
public class StructuredSampleService {

    /**
     * 演示用工单结构。
     *
     * @param title    短标题
     * @param priority P0–P3
     * @param labels   标签
     * @param summary  一句话摘要
     */
    public record Ticket(String title, String priority, List<String> labels, String summary) {}

    private final LlmProviderRegistry registry; // 提供商选择器
    private final StructuredOutputInvoker invoker; // 结构化调用
    private final String systemPrompt; //  系统提示词 作用：告诉模型如何生成 JSON 例如：把用户描述解析为工单 JSON

    /**
     * 启动时加载 {@code prompts/extract-ticket.st}，避免把长 prompt 写进业务方法。
     *
     * @param registry        按请求选择 Provider
     * @param invoker         带重试的结构化调用
     * @param promptLoader    加载 {@code prompts/extract-ticket.st}
     * @throws IOException 模板读失败
     */
    public StructuredSampleService(
        LlmProviderRegistry registry,
        StructuredOutputInvoker invoker,
        PromptLoader promptLoader
    ) throws IOException {
        this.registry = registry;
        this.invoker = invoker;
        this.systemPrompt = promptLoader.load("extract-ticket.st");
    }

    /**
     * 工单抽取结果。
     *
     * @param ticket 解析后的工单
     * @param usage  token 用量，网关未返回时为 {@code null}
     */
    public record ExtractResult(Ticket ticket, TokenUsage usage) {}

    /**
     * 把用户描述解析为 {@link Ticket}。
     *
     * @param userText 工单自然语言
     * @param provider Provider id，空则用默认 DeepSeek
     * @return 解析后的工单与 token 用量
     */
    public ExtractResult extract(String userText, String provider) {
        StructuredOutputInvoker.InvokeResult<Ticket> result =
            invoker.invoke(registry.plainClient(provider), systemPrompt, userText, Ticket.class);
        return new ExtractResult(result.value(), result.usage());
    }
}
