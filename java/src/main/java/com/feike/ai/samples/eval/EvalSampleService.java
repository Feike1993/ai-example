package com.feike.ai.samples.eval;

import com.feike.ai.core.TokenUsage;
import com.feike.ai.samples.agent.AgentSampleService;
import com.feike.ai.samples.agent.ReactAgentLoop;
import com.feike.ai.samples.eval.EvalGoldenLoader.EvalCase;
import com.feike.ai.samples.multiagent.MultiAgentSampleService;
import com.feike.ai.samples.rag.RagSampleService;
import com.feike.ai.samples.tools.ToolSampleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 评测 harness：对 golden 用例跑现有样例 Service 并做字符串 / 轨迹断言。
 */
@Service
public class EvalSampleService {

    private final EvalGoldenLoader goldenLoader;
    private final ToolSampleService toolSampleService;
    private final AgentSampleService agentSampleService;
    private final MultiAgentSampleService multiAgentSampleService;
    private final RagSampleService ragSampleService;

    public EvalSampleService(
        EvalGoldenLoader goldenLoader,
        ToolSampleService toolSampleService,
        AgentSampleService agentSampleService,
        MultiAgentSampleService multiAgentSampleService,
        @Autowired(required = false) RagSampleService ragSampleService
    ) {
        this.goldenLoader = goldenLoader;
        this.toolSampleService = toolSampleService;
        this.agentSampleService = agentSampleService;
        this.multiAgentSampleService = multiAgentSampleService;
        this.ragSampleService = ragSampleService;
    }

    /**
     * 跑完整 golden suite。
     *
     * @param provider Chat Provider；空则用默认
     * @return 汇总报告
     */
    public EvalRunResult runAll(String provider) {
        List<EvalCaseResult> cases = new ArrayList<>();
        int totalUsagePrompt = 0;
        int totalUsageCompletion = 0;
        int totalUsageTotal = 0;
        int usageCount = 0;

        for (EvalCase evalCase : goldenLoader.loadAll()) {
            EvalCaseResult result = runCase(evalCase, provider);
            cases.add(result);
            if (result.usage() != null) {
                usageCount++;
                totalUsagePrompt += nullSafe(result.usage().prompt());
                totalUsageCompletion += nullSafe(result.usage().completion());
                totalUsageTotal += nullSafe(result.usage().total());
            }
        }

        long passed = cases.stream().filter(EvalCaseResult::passed).count();
        long failed = cases.size() - passed;
        TokenUsage usageSummary = usageCount > 0
            ? new TokenUsage(totalUsagePrompt, totalUsageCompletion, totalUsageTotal)
            : null;
        return new EvalRunResult(cases.size(), (int) passed, (int) failed, cases, usageSummary);
    }

    /**
     * 跑单条用例（供单测或调试）。
     */
    public EvalCaseResult runCase(EvalCase evalCase, String provider) {
        long started = System.currentTimeMillis();
        try {
            return switch (evalCase.target()) {
                case "tools" -> evaluateTools(evalCase, provider, started);
                case "agentReact" -> evaluateAgentReact(evalCase, provider, started);
                case "rag" -> evaluateRag(evalCase, provider, started);
                case "multiagent" -> evaluateMultiAgent(evalCase, provider, started);
                default -> failCase(evalCase, started, "未知 target: " + evalCase.target(), 0, 0, null);
            };
        } catch (Exception ex) {
            return failCase(evalCase, started, ex.getMessage(), 0, 0, null);
        }
    }

    private EvalCaseResult evaluateTools(EvalCase evalCase, String provider, long started) {
        ToolSampleService.ToolChatResult result = toolSampleService.chatWithTools(evalCase.prompt(), provider);
        String answer = result.content() == null ? "" : result.content();
        List<String> errors = assertText(evalCase, answer);
        return buildResult(evalCase, started, errors.isEmpty(), answer, 0, 0, result.usage(), errors);
    }

    private EvalCaseResult evaluateAgentReact(EvalCase evalCase, String provider, long started) {
        ReactAgentLoop.Trace trace = agentSampleService.react(
            evalCase.prompt(),
            evalCase.maxSteps(),
            provider
        );
        String answer = trace.finalAnswer() == null ? "" : trace.finalAnswer();
        List<String> errors = assertText(evalCase, answer);
        int steps = trace.steps() == null ? 0 : trace.steps().size();
        int toolFailures = countToolFailures(trace.steps());
        if (Boolean.TRUE.equals(evalCase.expectSources())) {
            errors.add("agentReact 不支持 expectSources");
        }
        if (evalCase.expectToolName() != null && !evalCase.expectToolName().isBlank()) {
            boolean found = trace.steps() != null && trace.steps().stream()
                .anyMatch(s -> evalCase.expectToolName().equals(s.toolName()));
            if (!found) {
                errors.add("未调用工具: " + evalCase.expectToolName());
            }
        }
        if (trace.reachedMaxSteps()) {
            errors.add("触达 maxSteps 上限");
        }
        return buildResult(evalCase, started, errors.isEmpty(), answer, steps, toolFailures, null, errors);
    }

    private EvalCaseResult evaluateRag(EvalCase evalCase, String provider, long started) {
        if (ragSampleService == null) {
            return failCase(evalCase, started, "RAG 未启用（app.ai.rag.enabled=false）", 0, 0, null);
        }
        RagSampleService.RagQueryResult result = ragSampleService.query(evalCase.prompt(), provider, null);
        String answer = result.answer() == null ? "" : result.answer();
        List<String> errors = assertText(evalCase, answer);
        if (Boolean.TRUE.equals(evalCase.expectSources()) && (result.sources() == null || result.sources().isEmpty())) {
            errors.add("期望 sources 非空");
        }
        if (result.retrievalEmpty()) {
            errors.add("retrievalEmpty=true");
        }
        return buildResult(evalCase, started, errors.isEmpty(), answer, 0, 0, result.usage(), errors);
    }

    private EvalCaseResult evaluateMultiAgent(EvalCase evalCase, String provider, long started) {
        MultiAgentSampleService.MultiAgentResult result = multiAgentSampleService.run(evalCase.prompt(), provider);
        String answer = result.finalAnswer() == null ? "" : result.finalAnswer();
        List<String> errors = assertText(evalCase, answer);
        int steps = result.agents() == null ? 0 : result.agents().stream()
            .mapToInt(a -> a.steps() == null ? 0 : a.steps().size())
            .sum();
        if (result.reachedMaxSteps()) {
            errors.add("触达 Orchestrator / Worker 步数上限");
        }
        return buildResult(evalCase, started, errors.isEmpty(), answer, steps, 0, null, errors);
    }

    private static List<String> assertText(EvalCase evalCase, String answer) {
        List<String> errors = new ArrayList<>();
        for (String fragment : evalCase.mustContain()) {
            if (!answer.contains(fragment)) {
                errors.add("缺少 mustContain: " + fragment);
            }
        }
        for (String fragment : evalCase.mustNotContain()) {
            if (answer.contains(fragment)) {
                errors.add("命中 mustNotContain: " + fragment);
            }
        }
        return errors;
    }

    private static int countToolFailures(List<ReactAgentLoop.Step> steps) {
        if (steps == null) {
            return 0;
        }
        int failures = 0;
        for (ReactAgentLoop.Step step : steps) {
            String result = step.toolResult() == null ? "" : step.toolResult();
            if (result.contains("错误") || result.contains("失败") || result.contains("Exception")) {
                failures++;
            }
        }
        return failures;
    }

    private EvalCaseResult buildResult(
        EvalCase evalCase,
        long started,
        boolean passed,
        String answer,
        int steps,
        int toolFailures,
        TokenUsage usage,
        List<String> errors
    ) {
        return new EvalCaseResult(
            evalCase.id(),
            passed,
            System.currentTimeMillis() - started,
            steps,
            toolFailures,
            errors.isEmpty() ? null : String.join("; ", errors),
            answer,
            usage
        );
    }

    private EvalCaseResult failCase(
        EvalCase evalCase,
        long started,
        String error,
        int steps,
        int toolFailures,
        TokenUsage usage
    ) {
        return new EvalCaseResult(
            evalCase.id(),
            false,
            System.currentTimeMillis() - started,
            steps,
            toolFailures,
            error,
            null,
            usage
        );
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * @param usageSummary 各用例 usage 简单累加（null 字段按 0）；无 usage 时为 null
     */
    public record EvalRunResult(
        int total,
        int passed,
        int failed,
        List<EvalCaseResult> cases,
        TokenUsage usageSummary
    ) {}

    /**
     * @param steps        Agent 步数或 0
     * @param toolFailures 工具执行失败计数
     * @param error        失败原因；通过时为 null
     * @param answer       样例输出摘要（便于前端展开）
     * @param usage        单条 token 用量（若样例有返回）
     */
    public record EvalCaseResult(
        String id,
        boolean passed,
        long durationMs,
        int steps,
        int toolFailures,
        String error,
        String answer,
        TokenUsage usage
    ) {}
}
