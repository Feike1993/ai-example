import type { SampleGuideData } from './types'

/** 语义分块样例讲解：token vs 结构感知切分。 */
export const semanticChunkGuide: SampleGuideData = {
  title: '语义分块',
  concepts: [
    'token 切：固定长度，简单但可能切断章节。',
    'semantic（本仓）：按 Markdown 标题 / 空行切再软合并，无 LLM。',
    '两套索引用不同 corpus 并存；默认 query 仍走 token，保证旧样例。',
    '与 Hybrid / HyDE 正交，可叠加 retrievalMode / queryExpansion。',
  ],
  logic: {
    title: '对照逻辑',
    steps: [
      {
        title: 'ingest strategy=all',
        detail: '分别写入 ai-example-demo 与 ai-example-demo-semantic。',
      },
      {
        title: 'compare-chunking',
        detail: '同一问题对两套 corpus 做向量检索，默认只比 sources。',
      },
    ],
  },
  backend: [
    {
      label: '语义切分 — SemanticMarkdownSplitter',
      language: 'java',
      code: `List<Document> chunks = semanticSplitter.apply(sourceDocs);
// metadata: chunking=semantic, heading=...`,
    },
  ],
  frontend: [
    {
      label: '对照两路命中',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query/compare-chunking\`, { question, provider })`,
    },
  ],
}
