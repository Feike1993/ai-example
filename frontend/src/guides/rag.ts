import type { SampleGuideData } from './types'

/** RAG 样例讲解：分块、Embedding、pgvector、sources。 */
export const ragGuide: SampleGuideData = {
  title: 'RAG',
  concepts: [
    '离线：文档 → 分块 → Embedding → pgvector；在线：检索 topK → 拼上下文 → Chat。',
    'Embedding 与 Chat Provider 分离：本仓 Embedding 固定 DashScope text-embedding-v3（1024 维）。',
    'GIGO：检索差则生成易胡说；看 sources 核对答案出处。',
    '空检索：命中 < min-sources 时 retrievalEmpty=true；默认 skip-llm-when-empty 直接拒答，不编造。',
    '先 docker compose up -d，再 ingest，最后 query / SSE。',
  ],
  logic: {
    title: 'RAG 底层逻辑',
    steps: [
      {
        title: '分块权衡',
        detail: '块太大丢精度，太小丢语义；本演示用 TokenTextSplitter 固定 chunkSize。',
      },
      {
        title: '向量检索只是召回',
        detail: '相似度高不等于事实对；必须把原文片段交给模型，并在 UI 展示 sources。',
      },
      {
        title: '幂等 ingest',
        detail: '按 corpus 元数据删旧 chunk 再写入，避免重复索引把召回冲乱。',
      },
    ],
  },
  backend: [
    {
      label: '检索 + 生成 — RagSampleService',
      language: 'java',
      code: `List<Document> hits = vectorStore.similaritySearch(
    SearchRequest.builder().query(question).topK(k).build());
if (hits.size() < minSources && skipLlmWhenEmpty) {
  return new RagQueryResult(EMPTY_REFUSAL, List.of(), true, null);
}
String context = buildContext(hits);
return registry.plainClient(provider)
    .prompt()
    .system("只根据检索上下文回答；不足时说不知道。")
    .user("检索上下文：\\n" + context + "\\n\\n用户问题：" + question)
    .call()
    .content();`,
    },
  ],
  frontend: [
    {
      label: 'ingest + query',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/ingest\`, {})
const data = await postJson<RagQueryResponse>(
  \`\${API_BASE}/rag/query\`,
  { question, provider },
)`,
    },
  ],
}
