package com.feike.ai.samples.guardrail;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.samples.structured.StructuredOutputInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 护栏：输入词表短路不调 LLM；DenyWordChecker 命中规则。
 */
@DisplayName("GuardrailSampleService")
class GuardrailSampleServiceTest {

    @Test
    void denyWordCheckerShouldMatchCaseInsensitive() {
        assertEquals("违禁演示词", DenyWordChecker.firstHit("含有违禁演示词的句子", List.of("违禁演示词")));
        assertEquals("BLOCKED_DEMO", DenyWordChecker.firstHit("xx blocked_demo yy", List.of("BLOCKED_DEMO")));
        assertNull(DenyWordChecker.firstHit("正常问题", List.of("违禁演示词")));
    }

    @Test
    void shouldBlockInputWithoutCallingLlm() throws Exception {
        LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        PromptLoader promptLoader = mock(PromptLoader.class);
        when(promptLoader.load(anyString())).thenReturn("system");

        AiProperties properties = new AiProperties(
            "deepseek",
            0.2,
            Map.of(),
            new AiProperties.Structured(2, true, true, true, 200, false),
            new AiProperties.Agent(8),
            "dashscope",
            new AiProperties.Embedding("text-embedding-v3", 1024),
            new AiProperties.Rag(true, 4, 400, 1, true, new AiProperties.Rag.Hybrid(true, 60, 4, false), new AiProperties.Rag.Hyde(true, true), new AiProperties.Rag.Chunking("ai-example-demo-semantic", "ai-example-demo-parent", 200, true)),
            new AiProperties.ContextSettings(24, 2000, 6, "memory"),
            new AiProperties.MultiAgent(4, 6),
            new AiProperties.Memory(4, "demo", 0.92, 5),
            new AiProperties.Mcp("inprocess", "dev-mcp-token"),
            new AiProperties.Guardrail(List.of("违禁演示词"))
        );

        GuardrailSampleService service =
            new GuardrailSampleService(registry, properties, invoker, promptLoader);

        GuardrailSampleService.GuardrailResult result =
            service.chat("请解释违禁演示词", "deepseek", false);

        assertTrue(result.blocked());
        assertEquals("input_deny", result.blockStage());
        assertEquals(GuardrailSampleService.BLOCKED_REFUSAL, result.answer());
        assertFalse(result.checks().isEmpty());
        assertEquals("input_deny", result.checks().getFirst().name());
        assertFalse(result.checks().getFirst().passed());
        verify(registry, never()).plainClient(anyString());
    }
}
