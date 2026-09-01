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
    problem: '样例与 Prompt 改动后缺少可重复的回归手段，靠人工点几下容易漏回归。',
    purpose: '用最小 offline harness 对 tools/agent/rag/multiagent 等目标跑断言，快速发现破坏性改动。',
    pros: [
      '可脚本化、可进 CI 思路清晰。',
      '复用已有样例目标，增量加 case 成本低。',
      '失败信息比「感觉不对」可定位。',
    ],
    cons: [
      '本仓是最小断言，非完整评测平台。',
      '断言过严易脆，过松易漏。',
      '不覆盖线上真实流量分布。',
    ],
    scenarios: [
      '改 Prompt / 工具 / 检索后的冒烟回归。',
      '演示「评测在 Harness 中的位置」。',
    ],
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
