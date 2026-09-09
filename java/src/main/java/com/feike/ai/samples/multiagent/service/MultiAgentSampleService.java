package com.feike.ai.samples.multiagent.service;

import java.util.List;

/** MultiAgentSampleService 业务门面。 */
public interface MultiAgentSampleService {

    record OrchestratorDecision(String next, String task, String reason) {}
    record MultiAgentResult(String finalAnswer, List<AgentTraceView> agents, boolean reachedMaxSteps) {}
    record AgentTraceView(String name, String role, List<StepView> steps, String error) {}
    record StepView(int index, String assistantText, String toolName, String toolArgs, String toolResult) {}
    MultiAgentResult run(String prompt, String provider);
}
