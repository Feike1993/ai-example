import type { SampleGuideData } from './types'

/** Hybrid RAG 样例讲解：PG 全文、RRF、compare 对照。 */
export const hybridRagGuide: SampleGuideData = {
  title: 'Hybrid RAG',
  concepts: [
    '纯向量擅长语义相似，但可能漏掉关键词精确匹配（如 MCP、pgvector）。',
    'Hybrid 增加 PostgreSQL plainto_tsquery 关键词路，用 RRF 融合两路排名。',
    'RRF 公式 score(d) = Σ 1/(k + rank_i(d))；本仓 k 默认 60。',
    '默认 retrievalMode=vector 与二期一致；hybrid 才走双路 + RRF。',
    'sources 带 vectorRank / keywordRank / rrfScore，便于理解为何 hybrid 多召回了该 chunk。',
    'compare 接口同一问题并排返回 vector 与 hybrid 两套结果。',
  ],
  logic: {
    title: 'Hybrid 检索逻辑',
    steps: [
      {
        title: '双路召回',
        detail: '向量路用 pgvector 相似度；关键词路用 PG 全文 GIN 索引。两路各自产出 ranked list。',
      },
      {
        title: 'RRF 融合',
        detail: '不手工加权两路分数，而是按排名倒数求和。某文档在任一路排名靠前都会抬高 RRF 分。',
      },
      {
        title: '与基础 RAG 的关系',
        detail: 'ingest / Embedding / 表结构不变；ingest 后额外建 content 全文索引。进阶 Tab 专注对照与调参。',
      },
    ],
  },
  backend: [
    {
      label: 'Hybrid 检索 — RagSampleService.hybridRetrieve',
      language: 'java',
      code: `List<Document> vectorHits = vectorStore.similaritySearch(...);
List<Document> keywordHits = keywordRetriever.search(question, keywordK);
List<Document> fused = RrfFusion.fuse(vectorHits, keywordHits, rrfK);
return fused.stream().limit(k).toList();`,
    },
    {
      label: '并排对照 — POST /rag/query/compare',
      language: 'java',
      code: `RagQueryResult vector = query(question, provider, topK, vector, rewriteQuery);
RagQueryResult hybrid = query(question, provider, topK, hybrid, rewriteQuery);
return new CompareResult(vector, hybrid);`,
    },
  ],
  frontend: [
    {
      label: 'compare 并排',
      language: 'tsx',
      code: `const data = await postJson<RagCompareResponse>(
  \`\${API_BASE}/rag/query/compare\`,
  { question, provider, retrievalMode: 'hybrid', rewriteQuery },
)
// data.vector / data.hybrid 各含 sources + answer`,
    },
  ],
}
