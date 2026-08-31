import type { SampleGuideData } from './types'

/** HyDE 样例讲解：假想文档 Embedding 检索。 */
export const hydeGuide: SampleGuideData = {
  title: 'HyDE',
  concepts: [
    'HyDE = Hypothetical Document Embeddings（假想文档嵌入）：用「假想段落」的向量去检索真实文档，而不是直接 Embedding 用户短问句。',
    '问题表述与文档表述常有鸿沟：短问句不一定贴近知识库段落。',
    '流程：先让 Chat 写一段「假想知识库段落」，再 Embedding 该段落做向量检索。',
    '假想文档只用于检索；sources 与最终答案必须 grounded 在真实 chunk。',
    '可与原问题向量路 RRF 融合（fuse-with-original）；也可对照 none / rewrite。',
  ],
  logic: {
    title: 'HyDE 检索逻辑',
    steps: [
      {
        title: '生成假想段落',
        detail: '用 Chat 根据用户问题写百科风格段落；失败时回退原问题检索。',
      },
      {
        title: 'Embedding 假想段落检索',
        detail: 'similaritySearch 的 query 是假想正文，不是用户原问；语料仍是 ai-example-demo。',
      },
      {
        title: '生成仍用原问题 + 真实 sources',
        detail: '假想段落出现在响应字段 hypotheticalDocument 供预览，绝不写入 sources。',
      },
    ],
  },
  backend: [
    {
      label: '查询扩展 — RagSampleService',
      language: 'java',
      code: `// queryExpansion: none | rewrite | hyde
String hypo = generateHypotheticalDocument(question, provider);
List<Document> hits = vectorRetrieve(hypo, k);
// 生成：用户原问题 + 真实 hits`,
    },
    {
      label: '对照三套命中',
      language: 'bash',
      code: `curl -s .../rag/query/compare-expansion \\
  -d '{"question":"...","provider":"deepseek"}'`,
    },
  ],
  frontend: [
    {
      label: '切换扩展策略',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query\`, {
  question, provider, queryExpansion: 'hyde',
})`,
    },
  ],
}
