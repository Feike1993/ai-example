import type { SampleGuideData } from './types'

/** 多 Agent 样例讲解。 */
export const multiAgentGuide: SampleGuideData = {
  title: '多 Agent',
  concepts: [
    '多 Agent ≠ 多个 API Key；核心是信息边界与交接。',
    'Orchestrator 决策 next；researcher 挂工具；writer 执笔汇总。',
    '对比单 Agent 把全部工具挂一人：职责更清晰，轨迹更好读。',
    '可观测优先看 agents[].steps 与 error，而不是先上评测平台。',
  ],
  logic: {
    title: 'Orchestrator–Subagent',
    problem: '单一 Agent 身兼调研与执笔时易幻觉数字、工具边界模糊，长轨迹难控。',
    purpose: '编排者结构化分工：调研专员带工具取材料，执笔专员无工具只生成终答。',
    pros: [
      '职责分离，执笔阶段不易再编造工具结果。',
      '可复用已有 ReAct / Structured 能力。',
      '编排决策可观测（next 枚举）。',
    ],
    cons: [
      '多轮 LLM，成本与延迟高于单 Agent。',
      '编排错误会导致错派专员。',
      '专员间上下文传递需精心设计。',
    ],
    scenarios: [
      '先检索/算数再成文的报告类任务。',
      '需要「调研」与「写作」权限隔离的流程。',
    ],
    steps: [
      {
        title: '编排者结构化决策',
        detail: '用 StructuredOutputInvoker 解析 next=researcher|writer。',
      },
      {
        title: '调研专员复用 ReAct',
        detail: '挂 DemoTools，受限 maxWorkerSteps，材料写回列表。',
      },
      {
        title: '执笔专员无工具',
        detail: '只根据材料生成最终答复，避免再编造天气数字。',
      },
    ],
  },
  backend: [
    {
      label: '运行 — MultiAgentSampleService',
      language: 'java',
      code: `OrchestratorDecision d = decide(prompt, materials, provider);
if ("researcher".equals(d.next())) {
  Trace t = ReactAgentLoop.run(chatModel, demoTools, ..., task, workerSteps);
  materials.add(t.finalAnswer());
}`,
    },
  ],
  frontend: [
    {
      label: '调用 multiagent/run',
      language: 'tsx',
      code: `const data = await postJson<MultiAgentResult>(
  \`\${API_BASE}/multiagent/run\`,
  { prompt, provider },
)`,
    },
  ],
}
