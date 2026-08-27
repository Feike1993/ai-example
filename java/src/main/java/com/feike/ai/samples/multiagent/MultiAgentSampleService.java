package com.feike.ai.samples.multiagent;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.samples.agent.ReactAgentLoop;
import com.feike.ai.samples.structured.StructuredOutputInvoker;
import com.feike.ai.samples.tools.DemoTools;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 多 Agent 样例：Orchestrator 交接 WorkerA（工具）/ WorkerB（执笔）。
 */
@Service
public class MultiAgentSampleService {

    private final LlmProviderRegistry registry;
    private final DemoTools demoTools;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final AiProperties.MultiAgent multiAgentSettings;
    private final String orchestratorPrompt;
    private final String researcherPrompt;
    private final String writerPrompt;

    /**
     * @param registry                 LLM
     * @param demoTools                WorkerA 工具
     * @param structuredOutputInvoker  Orchestrator 结构化决策
     * @param properties               步数配置
     * @param promptLoader             加载 multiagent 角色模板
     */
    public MultiAgentSampleService(
        LlmProviderRegistry registry,
        DemoTools demoTools,
        StructuredOutputInvoker structuredOutputInvoker,
        AiProperties properties,
        PromptLoader promptLoader
    ) throws IOException {
        this.registry = registry;
        this.demoTools = demoTools;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.multiAgentSettings = properties.multiagent();
        this.orchestratorPrompt = promptLoader.load("multiagent-orchestrator.st");
        this.researcherPrompt = promptLoader.load("multiagent-researcher.st");
        this.writerPrompt = promptLoader.load("multiagent-writer.st");
    }

    /**
     * 运行一轮多 Agent 协作。
     *
     * @param prompt   用户任务
     * @param provider Chat Provider
     * @return 最终答复与各角色轨迹
     */
    public MultiAgentResult run(String prompt, String provider) {
        List<AgentTraceView> agents = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        boolean reachedMax = false;
        int orchLimit = multiAgentSettings.maxOrchestratorSteps();
        int workerSteps = multiAgentSettings.maxWorkerSteps();

        for (int i = 1; i <= orchLimit; i++) {
            OrchestratorDecision decision;
            try {
                decision = decide(prompt, materials, provider);
            } catch (Exception ex) {
                agents.add(new AgentTraceView(
                    "orchestrator",
                    "编排",
                    List.of(),
                    ex.getMessage()
                ));
                return new MultiAgentResult(
                    "Orchestrator 决策失败: " + ex.getMessage(),
                    agents,
                    true
                );
            }
            agents.add(new AgentTraceView(
                "orchestrator",
                "编排",
                List.of(new StepView(i, decision.reason(), decision.next(), "", "")),
                null
            ));

            if ("finish".equalsIgnoreCase(decision.next()) || "writer".equalsIgnoreCase(decision.next())) {
                String finalAnswer = writeFinal(prompt, materials, provider);
                agents.add(new AgentTraceView("writer", "执笔", List.of(), null));
                return new MultiAgentResult(finalAnswer, agents, reachedMax);
            }
            // worker_a 出现在判断里，是因为路由层把旧名字当别名兼容（Python 版也一样：{"researcher", "worker_a"}）。
            // 类注释里写的 “WorkerA / WorkerB” 是角色昵称，真正约定给模型的 token 是 researcher / writer。
            if ("researcher".equalsIgnoreCase(decision.next()) || "worker_a".equalsIgnoreCase(decision.next())) {
                String task = decision.task() == null || decision.task().isBlank() ? prompt : decision.task();
                ReactAgentLoop.Trace trace = ReactAgentLoop.run(
                    registry.chatModel(provider),
                    demoTools,
                    researcherPrompt,
                    task,
                    workerSteps
                );
                materials.add("【调研专员】\n" + trace.finalAnswer());
                List<StepView> steps = trace.steps().stream()
                    .map(s -> new StepView(s.index(), s.assistantText(), s.toolName(), s.toolArgs(), s.toolResult()))
                    .toList();
                agents.add(new AgentTraceView("researcher", "调研/工具", steps, null));
                if (trace.reachedMaxSteps()) {
                    reachedMax = true;
                }
                continue;
            }

            // 未知 next：直接执笔收尾
            String finalAnswer = writeFinal(prompt, materials, provider);
            agents.add(new AgentTraceView("writer", "执笔", List.of(), "未知 next=" + decision.next() + "，已降级执笔"));
            return new MultiAgentResult(finalAnswer, agents, true);
        }

        String finalAnswer = writeFinal(prompt, materials, provider);
        agents.add(new AgentTraceView("writer", "执笔", List.of(), "触达 Orchestrator 步数上限"));
        return new MultiAgentResult(finalAnswer, agents, true);
    }

    private OrchestratorDecision decide(String prompt, List<String> materials, String provider) {
        String materialBlock = materials.isEmpty() ? "（尚无专员产出）" : String.join("\n\n", materials);
        return structuredOutputInvoker.invoke(
            registry.plainClient(provider),
            orchestratorPrompt,
            "用户任务：\n" + prompt + "\n\n已有材料：\n" + materialBlock,
            OrchestratorDecision.class
        ).value();
    }

    private String writeFinal(String prompt, List<String> materials, String provider) {
        String materialBlock = materials.isEmpty() ? "（无调研材料）" : String.join("\n\n", materials);
        String content = registry.plainClient(provider)
            .prompt()
            .system(writerPrompt)
            .user("用户任务：\n" + prompt + "\n\n材料：\n" + materialBlock)
            .call()
            .content();
        return content == null ? "" : content;
    }

    /**
     * Orchestrator 结构化决策。
     *
     * @param next   researcher / writer / finish
     * @param task   交给专员的子任务
     * @param reason 决策理由
     */
    public record OrchestratorDecision(String next, String task, String reason) {}

    /**
     * @param finalAnswer     最终答复
     * @param agents          各角色轨迹
     * @param reachedMaxSteps 是否触达任一熔断
     */
    public record MultiAgentResult(String finalAnswer, List<AgentTraceView> agents, boolean reachedMaxSteps) {}

    /**
     * @param name  角色 id
     * @param role  展示名
     * @param steps 步骤
     * @param error 可选错误
     */
    public record AgentTraceView(String name, String role, List<StepView> steps, String error) {}

    /**
     * @param index         步号
     * @param assistantText 模型文本
     * @param toolName      工具名或 next
     * @param toolArgs      参数
     * @param toolResult    结果
     */
    public record StepView(int index, String assistantText, String toolName, String toolArgs, String toolResult) {}
}
