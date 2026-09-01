import type { SampleGuideData } from './types'

/** 召回策略对照样例讲解。 */
export const memoryCompareGuide: SampleGuideData = {
  title: '召回策略对照',
  concepts: [
    '同一问题并排 lowTopK / highTopK / withThreshold 的 sources。',
    'chat/compare 对照有记忆 grounded 与无记忆纯 Chat。',
    'recall/compare 默认不三次生成，控成本。',
    '无 Document score 时阈值不生效，仅 topK。',
  ],
  logic: {
    title: '对照流程',
    steps: [
      { title: '准备事实', detail: 'remember 或 extract 写入 corpus。' },
      { title: '三路 recall', detail: '窄 / 宽 / 宽+阈值。' },
      { title: '有无记忆 chat', detail: '看答案是否被记忆约束。' },
    ],
  },
  backend: [
    {
      label: 'compareRecall',
      language: 'java',
      code: `recall(q, uid, low, null);
recall(q, uid, high, null);
recall(q, uid, high, threshold);`,
    },
  ],
  frontend: [
    {
      label: '调用对照',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/memory/recall/compare\`, { query, userId })`,
    },
  ],
}
