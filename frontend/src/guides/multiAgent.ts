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
