import type { SampleGuideData } from './types'

/** 输出护栏讲解（第十一期）。 */
export const guardrailGuide: SampleGuideData = {
  title: '输出护栏',
  concepts: [
    '确定性规则优先：词表命中即短路，不静默改写。',
    'input_deny →（可选）LLM → output_deny →（可选）structure。',
    '响应带回 checks[]，便于看到拦在哪一层。',
    'requireStructured 强制 SafeEnvelope；解析失败或 safe=false → 拒答。',
  ],
  logic: {
    title: '护栏管道',
    problem: '仅靠 prompt「不要胡说」无法保证输出合规；需要可观测的硬约束。',
    purpose: '用配置词表 + 可选结构校验演示答前/答后拦截。',
    pros: ['短路省费用', 'checks 可审计', '与 Chat 路径解耦'],
    cons: ['词表易漏报/误报', '非完整安全产品'],
    scenarios: ['演示拒答', '业务接入前理解护栏落点'],
    steps: [
      { title: '输入词表', detail: '命中 deny-words 则不调模型。' },
      { title: '生成', detail: '通过后调 Chat 或结构化信封。' },
      { title: '输出词表 / 结构', detail: '回复再扫一遍；结构失败拒答。' },
    ],
  },
  backend: [
    {
      label: 'DenyWordChecker',
      language: 'java',
      code: `String hit = DenyWordChecker.firstHit(prompt, denyWords);
if (hit != null) {
  return blocked("input_deny", ...);
}`,
    },
  ],
  frontend: [
    {
      label: 'POST /guardrail/chat',
      language: 'tsx',
      code: `await postJson('/ai-example/guardrail/chat', {
  prompt, provider, requireStructured,
})`,
    },
  ],
}
