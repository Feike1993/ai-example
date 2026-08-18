package com.feike.ai.samples.structured;

import com.feike.ai.core.LlmClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final LlmClientFactory factory;
    private final StructuredOutputInvoker invoker;
    private final String systemPrompt;

    /**
     * 启动时加载 {@code prompts/extract-ticket.st}，避免把长 prompt 写进业务方法。
     *
     * @param factory         提供 plain ChatClient
     * @param invoker         带重试的结构化调用
     * @param promptResource  classpath 上的提示词模板
     * @throws IOException 模板读失败
     */
    public StructuredSampleService(
        LlmClientFactory factory,
        StructuredOutputInvoker invoker,
        @Value("classpath:prompts/extract-ticket.st") Resource promptResource
    ) throws IOException {
        this.factory = factory;
        this.invoker = invoker;
        this.systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 把用户描述解析为 {@link Ticket}。
     *
     * @param userText 工单自然语言
     * @return 解析后的工单
     */
    public Ticket extract(String userText) {
        return invoker.invoke(factory.plainClient(), systemPrompt, userText, Ticket.class);
    }
}
