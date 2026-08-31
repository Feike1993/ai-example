package com.feike.ai.samples.structured;

import com.feike.ai.core.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@link StructuredOutputInvoker#parse} 走与生产相同的围栏剥离路径，不调用 LLM。
 */
@DisplayName("StructuredOutputInvoker.parse")
class StructuredOutputInvokerTest {

    /**
     * 测试用工单结构，字段需与生产 {@code Ticket} 对齐。
     *
     * @param title    标题
     * @param priority 优先级
     * @param labels   标签
     * @param summary  摘要
     */
    public record Ticket(String title, String priority, List<String> labels, String summary) {}

    @Test
    void shouldParseJsonAfterStrippingFence() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties());
        String raw = """
            ```json
            {"title":"登录 500","priority":"P1","labels":["backend","auth"],"summary":"登录页偶发 500"}
            ```
            """;
        Ticket ticket = invoker.parse(raw, Ticket.class);
        assertEquals("登录 500", ticket.title());
        assertEquals("P1", ticket.priority());
        assertEquals(List.of("backend", "auth"), ticket.labels());
    }

    private static AiProperties properties() {
        return new AiProperties(
            "deepseek",
            0.2,
            java.util.Map.of(),
            new AiProperties.Structured(2, true, true, true, 200, false),
            new AiProperties.Agent(8),
            "dashscope",
            new AiProperties.Embedding("text-embedding-v3", 1024),
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true)),
            new AiProperties.ContextSettings(24, 2000, 6, "memory"),
            new AiProperties.MultiAgent(4, 6),
            new AiProperties.Memory(4, "demo", 0.92),
            new AiProperties.Mcp("inprocess")
        );
    }
}
