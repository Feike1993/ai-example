package com.feike.ai.samples.multiagent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.samples.structured.StructuredOutputInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestrator 决策解析冒烟（走 StructuredOutputInvoker.parse，不调模型）。
 */
@DisplayName("MultiAgentOrchestrator")
class MultiAgentOrchestratorTest {

    @Test
    void shouldParseOrchestratorDecisionJson() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties());
        String raw = """
            {"next":"researcher","task":"查北京天气","reason":"需要工具"}
            """;
        MultiAgentSampleService.OrchestratorDecision decision =
            invoker.parse(raw, MultiAgentSampleService.OrchestratorDecision.class);
        assertEquals("researcher", decision.next());
        assertTrue(decision.task().contains("北京"));
        assertEquals("需要工具", decision.reason());
    }

    @Test
    void shouldParseWriterDecision() {
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(properties());
        MultiAgentSampleService.OrchestratorDecision decision = invoker.parse(
            "{\"next\":\"writer\",\"task\":\"\",\"reason\":\"材料已够\"}",
            MultiAgentSampleService.OrchestratorDecision.class
        );
        assertEquals("writer", decision.next());
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
