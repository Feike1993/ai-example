package com.feike.ai.samples.eval.service.impl;

import com.feike.ai.samples.eval.service.EvalGoldenLoader;
import com.feike.ai.samples.eval.service.EvalSampleService;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.samples.agent.service.AgentSampleService;
import com.feike.ai.samples.agent.service.ReactAgentLoop;
import com.feike.ai.samples.multiagent.service.MultiAgentSampleService;
import com.feike.ai.samples.rag.service.RagSampleService;
import com.feike.ai.samples.tools.service.ToolSampleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EvalSampleService 断言")
class EvalSampleServiceImplTest {

    @Test
    void toolsCaseShouldPassWhenMustContainMet() {
        ToolSampleService tools = mock(ToolSampleService.class);
        when(tools.chatWithTools(anyString(), isNull()))
            .thenReturn(new ToolSampleService.ToolChatResult("北京晴 25°C", new TokenUsageDTO(1, 2, 3)));

        EvalSampleService service = new EvalSampleServiceImpl(
            mock(EvalGoldenLoader.class),
            tools,
            mock(AgentSampleService.class),
            mock(MultiAgentSampleService.class),
            null
        );

        EvalGoldenLoader.EvalCase evalCase = new EvalGoldenLoader.EvalCase(
            "tools-demo",
            "tools",
            "查北京天气",
            List.of("北京", "25"),
            List.of(),
            null,
            null,
            null
        );

        EvalSampleService.EvalCaseResult result = service.runCase(evalCase, null);
        assertTrue(result.passed());
    }

    @Test
    void toolsCaseShouldFailWhenMustContainMissing() {
        ToolSampleService tools = mock(ToolSampleService.class);
        when(tools.chatWithTools(anyString(), isNull()))
            .thenReturn(new ToolSampleService.ToolChatResult("上海多云", null));

        EvalSampleService service = new EvalSampleServiceImpl(
            mock(EvalGoldenLoader.class),
            tools,
            mock(AgentSampleService.class),
            mock(MultiAgentSampleService.class),
            null
        );

        EvalGoldenLoader.EvalCase evalCase = new EvalGoldenLoader.EvalCase(
            "tools-demo",
            "tools",
            "查北京天气",
            List.of("北京"),
            List.of(),
            null,
            null,
            null
        );

        EvalSampleService.EvalCaseResult result = service.runCase(evalCase, null);
        assertFalse(result.passed());
        assertTrue(result.error().contains("mustContain"));
    }

    @Test
    void agentReactShouldCheckToolName() {
        AgentSampleService agent = mock(AgentSampleService.class);
        when(agent.react(anyString(), anyInt(), isNull())).thenReturn(new ReactAgentLoop.Trace(
            "北京 25 度",
            List.of(new ReactAgentLoop.Step(1, "", "getWeather", "{}", "ok")),
            false,
            null,
            0
        ));

        EvalSampleService service = new EvalSampleServiceImpl(
            mock(EvalGoldenLoader.class),
            mock(ToolSampleService.class),
            agent,
            mock(MultiAgentSampleService.class),
            null
        );

        EvalGoldenLoader.EvalCase evalCase = new EvalGoldenLoader.EvalCase(
            "agent-demo",
            "agentReact",
            "查天气",
            List.of("北京"),
            List.of(),
            8,
            null,
            "getWeather"
        );

        assertTrue(service.runCase(evalCase, null).passed());
    }
}
