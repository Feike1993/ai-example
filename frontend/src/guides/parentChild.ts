import type { SampleGuideData } from './types'

/** 父子文档样例讲解：子块检索、父块上下文。 */
export const parentChildGuide: SampleGuideData = {
  title: '父子文档',
  concepts: [
    '子块小、好检索；父块大、好生成。',
    '只 Embedding 子块；parentText 进 metadata，不另建表。',
    '命中后 expand-parent 按 parentId 去重再拼上下文。',
    'compare-chunking 第三路 parentChild 与 token/semantic 对照。',
  ],
  logic: {
    title: '父子流程',
    steps: [
      { title: 'ingest parent_child', detail: '语义切父块 → childSize 硬切子块入库。' },
      { title: '检索子块', detail: 'similaritySearch 仍过滤 corpus=ai-example-demo-parent。' },
      { title: '展开父块', detail: '生成用 parentText；sources 仍展示子块 + parentExcerpt。' },
    ],
  },
  backend: [
    {
      label: '展开',
      language: 'java',
      code: `List<Document> contextDocs = expandParents(hits);
String context = buildContext(contextDocs);`,
    },
  ],
  frontend: [
    {
      label: '单路 parent_child',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query\`, {
  question, provider, chunkingStrategy: 'parent_child',
})`,
    },
  ],
}
