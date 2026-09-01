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
    problem: '模型参数内知识过时且易幻觉；私有/长尾文档无法靠微调即时接入。',
    purpose: '离线分块 Embedding 入库，在线检索相关片段拼进提示，让回答 grounded 在 sources。',
    pros: [
      '知识可更新：改文档再 ingest，无需重训模型。',
      '可展示出处，便于人工核对。',
      '与 Chat Provider 解耦（本仓 Embedding 固定）。',
    ],
    cons: [
      '分块与检索质量决定上限（GIGO）。',
      '固定长度切分易切断章节语义。',
      '空检索若仍生成，易编造；需拒答策略。',
    ],
    scenarios: [
      '企业知识库问答、产品文档助手。',
      '需要可追溯引用来源的答疑场景。',
    ],
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
