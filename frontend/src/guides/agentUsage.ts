import type { SampleGuideData } from './types'

/** 流式 / 多轮 TokenUsage 累加讲解。 */
export const agentUsageGuide: SampleGuideData = {
  title: 'TokenUsage 累加',
  concepts: [
    'Agent 多轮 ChatModel.call，需把各轮 usage 相加。',
    '同步 Trace.usage / usageCalls；流式 event:usage（含 calls）。',
    '网关未返回时字段为 null，不编造。',
    '与 Framework 单次 usage 对照。',
  ],
  logic: {
    title: '计费直觉',
    problem: '单次 TokenUsage 无法覆盖 ReAct 多跳。',
    purpose: '展示合计 prompt/completion/total 与调用次数。',
    pros: ['同步流式一致', '调用次数可观测'],
    cons: ['流式终答若无 metadata，completion 可能不全'],
    scenarios: ['粗算一次 Agent 任务成本'],
    steps: [
      { title: '每轮 extract', detail: 'TokenUsageExtractor.from(response)。' },
      { title: 'sum 累加', detail: 'TokenUsageExtractor.sum。' },
      { title: '推送合计', detail: 'Trace 或 event:usage。' },
    ],
  },
  backend: [
    {
      label: '累加',
      language: 'java',
      code: `usageAcc = TokenUsageExtractor.sum(usageAcc, callUsage);
usageCalls++;`,
    },
  ],
  frontend: [
    {
      label: '展示',
      language: 'tsx',
      code: `<RequestMeta usage={trace.usage} />`,
    },
  ],
}
