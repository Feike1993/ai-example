import type { SampleGuideData } from './types'

/** Agent 评测 golden suite 讲解。 */
export const evalGuide: SampleGuideData = {
  title: 'Agent 评测',
  concepts: [
    'golden set：上线前可重复回归的输入 + 断言，比「感觉对了」可靠。',
    '本仓 harness：`classpath:eval/golden/*.json`，`POST /eval/run` 直接调现有 Service（非 HTTP 自调用）。',
    '断言：mustContain / mustNotContain；Agent 检查 tool 名与 maxSteps；RAG 检查 sources 非空。',
    '报告：通过率、单条耗时、步数、toolFailures、usage 汇总（若网关返回）。',
  ],
  logic: {
    title: '评测层在架构中的位置',
    steps: [
      {
        title: 'Harness 里的 Evaluator',
        detail: '与 Memory / Tools / Agent 并列；这里只做最小 offline 断言，不做 LangSmith 式 tracing 产品。',
      },
      {
        title: 'target 枚举',
        detail: 'tools | agentReact | rag | multiagent — 复用一至三期样例，便于增量加 case。',
      },
    ],
  },
  backend: [
    {
      label: 'EvalSampleService.runAll',
      language: 'java',
      code: `@PostMapping("/eval/run")
public EvalRunResult run(@RequestBody EvalRunRequest request) {
  return evalSampleService.runAll(request.provider());
}`,
    },
  ],
  frontend: [
    {
      label: '一键跑 suite',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/eval/run\`, { provider })`,
    },
  ],
}
