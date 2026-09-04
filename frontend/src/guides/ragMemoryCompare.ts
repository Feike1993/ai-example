import type { SampleGuideData } from './types'

/** RAG vs 记忆对照讲解（第十二期）。 */
export const ragMemoryCompareGuide: SampleGuideData = {
  title: 'RAG vs 记忆对照',
  concepts: [
    '同问双路：知识库 RAG vs 长期记忆。',
    '看清「文档问题」与「个人事实」该走哪条。',
    'EMPTY=false 只表示有 sources；复合问应偏答能支撑的半边，而非整题不知道。',
    'generateAnswers=false 只比 sources，控成本。',
  ],
  logic: {
    title: '边界可视化',
    problem: '学习者易把记忆与 RAG 混为一谈；或把 EMPTY=false 当成「应答全题」。',
    purpose: '并排 sources / 答案，强化 corpus 隔离；文档半边走 RAG，个人半边走记忆。',
    pros: ['对照直观', '对齐既有 compare API 形态'],
    cons: ['双路生成成本高', '不自动融合答案'],
    scenarios: ['教学演示', '排查答非所问'],
    steps: [
      { title: 'RAG 路', detail: '演示语料检索（可选生成）；复合问只答上下文能支撑的子问。' },
      { title: 'Memory 路', detail: 'long-term-memory + userId。' },
      { title: '对照', detail: '看 empty 与答案差异，勿把整题拒答当成检索失败。' },
    ],
  },
  backend: [
    {
      label: 'compare-memory',
      language: 'java',
      code: `POST /rag/query/compare-memory
→ { rag, memory, generateAnswers }`,
    },
  ],
  frontend: [
    {
      label: '双栏',
      language: 'tsx',
      code: `postJson('/ai-example/rag/query/compare-memory', {
  question, userId, generateAnswers,
})`,
    },
  ],
}
