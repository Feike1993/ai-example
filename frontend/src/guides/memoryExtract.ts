import type { SampleGuideData } from './types'

/** 自动抽记忆样例讲解。 */
export const memoryExtractGuide: SampleGuideData = {
  title: '自动抽记忆',
  concepts: [
    '从对话抽出短事实句，再走 remember（去重 / 相似合并）。',
    '显式 POST /memory/extract，不在 context/chat 静默写入。',
    '可传 messages，或只传 sessionId 从会话快照抽取。',
    'extract-max-facts 限制单次写入量。',
  ],
  logic: {
    title: '抽取流程',
    problem:
      '长期记忆靠手工 remember 时，用户不会主动「登记事实」；若在每轮 context/chat 里静默抽取，又难审计、难控写入量。',
    purpose:
      '用一次显式 Chat 从对话抽出候选短事实，再复用现有 remember 契约落库；抽取与持久化解耦，失败可空列表降级。',
    pros: [
      '补齐「对话 → 事实库」闭环，不必手写每条记忆。',
      '写入仍走 remember（去重/合并），契约与样例 12 一致。',
      '显式接口，可审计、可限流（extract-max-facts）。',
    ],
    cons: [
      '多一次 LLM，延迟与费用上升；解析失败则 facts 为空。',
      '抽取质量依赖 Prompt 与模型，可能漏抽或抽错。',
      '不覆盖静默每轮抽取与召回策略对照（见后续样例）。',
    ],
    scenarios: [
      '对话结束后批量沉淀用户画像/偏好。',
      '从已有 session 快照补写长期记忆。',
    ],
    steps: [
      { title: '拼对话', detail: 'messages / turns / session 快照三选一。' },
      { title: 'Chat 抽 JSON 数组', detail: '失败则空列表，不拖垮接口。' },
      { title: '逐条 remember', detail: '写入 long-term-memory corpus。' },
    ],
  },
  backend: [
    {
      label: 'extract',
      language: 'java',
      code: `List<String> facts = parseFactList(raw, maxFacts);
for (String fact : facts) remember(fact, userId, sessionId);`,
    },
  ],
  frontend: [
    {
      label: '调用抽取',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/memory/extract\`, { userId, messages, provider })`,
    },
  ],
}
