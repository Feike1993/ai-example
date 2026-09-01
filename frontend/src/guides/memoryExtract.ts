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
