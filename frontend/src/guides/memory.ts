import type { SampleGuideData } from './types'

/** 长期记忆样例讲解。 */
export const memoryGuide: SampleGuideData = {
  title: '长期记忆',
  concepts: [
    '会话窗口是短期；长期记忆是跨会话的个人事实库。',
    'corpus=long-term-memory，与 RAG 演示语料 ai-example-demo 隔离。',
    '流程：remember 写入（精确跳过 / 相似合并）→ recall → chat。',
    '空召回时拒答，不编造记忆外内容。',
    '回答时可轻度修正明显笔误；实质事实仍须来自记忆。',
  ],
  logic: {
    title: '记忆与 RAG 的边界',
    problem: '会话裁剪后长期偏好/事实会丢；若把一切塞进短上下文又贵又不稳。',
    purpose: '把长期事实显式写入独立 corpus，按 userId 召回，与文档 RAG 共用向量库但语义分离。',
    pros: [
      '跨会话保留用户级事实。',
      '与知识库 corpus 隔离，减少串味。',
      '写入显式，便于治理与删除。',
    ],
    cons: [
      '本样例不自动抽取，靠调用方 remember。',
      '事实冲突、过期需额外策略。',
      '召回质量仍受分块与 Embedding 影响。',
    ],
    scenarios: [
      '记住用户昵称、偏好、项目背景。',
      '个人助理中「长期画像」与「文档问答」并存。',
    ],
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
