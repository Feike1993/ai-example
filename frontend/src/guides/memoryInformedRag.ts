import type { SampleGuideData } from './types'

/** 记忆辅助 RAG 改写讲解（第十二期）。 */
export const memoryInformedRagGuide: SampleGuideData = {
  title: '记忆辅助改写',
  concepts: [
    'memory_rewrite：先 recall 个人事实，再改写检索短句。',
    'memoryHints 不进 RAG sources；语料继续隔离。',
    '无记忆时回退普通 rewrite。',
  ],
  logic: {
    title: '记忆作查询先验',
    problem: '口语问题缺实体时，纯 rewrite 仍可能偏题。',
    purpose: '用长期记忆消歧，再检索知识库。',
    pros: ['不污染 KB sources', '复用现有 rewrite / recall'],
    cons: ['多一次 recall + 改写调用', '记忆噪声可能误导改写'],
    scenarios: ['个性化检索', '对照 none / rewrite / memory_rewrite'],
    steps: [
      { title: 'recall', detail: '按 userId 取 memoryHints。' },
      { title: 'rewrite', detail: '带 hints 的改写 prompt。' },
      { title: 'retrieve', detail: '只用 rewrittenQuery 查 RAG corpus。' },
    ],
  },
  backend: [
    {
      label: 'memory_rewrite 分支',
      language: 'java',
      code: `List<SourceView> hints = recallMemoryHints(...);
String rewritten = rewriteWithMemoryHints(question, provider, hints);
List<Document> hits = retrieveByMode(rewritten, ...);`,
    },
  ],
  frontend: [
    {
      label: 'queryExpansion',
      language: 'tsx',
      code: `postJson('/ai-example/rag/query', {
  question, queryExpansion: 'memory_rewrite', userId,
})`,
    },
  ],
}
