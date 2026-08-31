import type { SampleGuideData } from './types'

/** 长期记忆样例讲解。 */
export const memoryGuide: SampleGuideData = {
  title: '长期记忆',
  concepts: [
    '会话窗口是短期；长期记忆是跨会话的个人事实库。',
    'corpus=long-term-memory，与 RAG 演示语料 ai-example-demo 隔离。',
    '流程：remember 写入（精确跳过 / 相似合并）→ recall → chat。',
    '空召回时拒答，不编造记忆外内容。',
  ],
  logic: {
    title: '记忆与 RAG 的边界',
    steps: [
      {
        title: '同一 vector_store，不同 corpus',
        detail: '用 metadata 过滤 userId + corpus，不必新建物理表。',
      },
      {
        title: '写入是显式的',
        detail: '本样例不自动从对话抽取事实；生产可另加抽取 Agent。',
      },
    ],
  },
  backend: [
    {
      label: 'recall 过滤',
      language: 'java',
      code: `vectorStore.similaritySearch(
  SearchRequest.builder()
    .query(query).topK(k)
    .filterExpression("corpus == 'long-term-memory' && userId == 'demo'")
    .build());`,
    },
  ],
  frontend: [
    {
      label: 'remember + chat',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/memory/remember\`, { text, userId })
await postJson(\`\${API_BASE}/memory/chat\`, { prompt, userId, provider })`,
    },
  ],
}
