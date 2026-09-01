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
    problem:
      '只看单次 recall/chat 难以体会 topK、阈值与「有无记忆」对答案的影响，调参靠感觉。',
    purpose:
      '同一问题并排展示窄/宽/阈值三路 sources，以及有记忆 vs 无记忆 chat，用对照建立直觉。',
    pros: [
      '参数差异可视化，便于选 topK / 阈值。',
      'recall 对照默认可只比 sources，不三次生成，控成本。',
      '有无记忆对照能看出 grounded 与纯 Chat 的差异。',
    ],
    cons: [
      '阈值依赖 Document score；库未带回 score 时仅 topK 生效。',
      '对照接口偏演示，非生产评测看板。',
      'chat/compare 仍有两次生成开销。',
    ],
    scenarios: [
      '调长期记忆召回宽度与相似度门槛。',
      '向同事演示「记忆约束」相对裸 Chat 的效果。',
    ],
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
