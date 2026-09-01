import type { SampleGuideData } from './types'

/** 父子文档样例讲解：子块检索、父块上下文，核心决策行级对照。 */
export const parentChildGuide: SampleGuideData = {
  title: '父子文档',
  concepts: [
    '子块小、好检索；父块大、好生成——同一文档拆成两级粒度。',
    '只 Embedding / 入库子块；父全文进 metadata.parentText，不另建父向量表。',
    '父块来自语义切分（复用 SemanticMarkdownSplitter）；子块再按 childSize 硬切。',
    '命中后 expand-parent 按 parentId 去重，用父全文拼 LLM 上下文；sources 仍展示子块 + parentExcerpt。',
    '独立 corpus（默认 ai-example-demo-parent）；compare-chunking 第三路 parentChild 与 token/semantic 对照。',
    'expand-parent=false 时可关掉展开，生成也只用子块正文。',
  ],
  logic: {
    title: '父子管线',
    steps: [
      {
        title: '语义切父块',
        detail: 'semanticSplitter.apply：按 Markdown 标题/空行切出结构完整的父段。',
      },
      {
        title: '硬切子块',
        detail: '对每个父段 hardSplit(parentText, childSize)；空切结果跳过，避免空向量。',
      },
      {
        title: '只入库子块',
        detail:
          'metadata：chunkRole=child、parentId、parentText（整段父文）、chunkIndex；父块本身不入 VectorStore。',
      },
      {
        title: '检索子块',
        detail: 'similaritySearch 过滤 corpus=parent；命中的是短子块，召回更准。',
      },
      {
        title: '展开去重',
        detail:
          'expandParents：同 parentId 只留一份父 Document；非 child 命中透传，避免丢上下文。',
      },
      {
        title: 'context vs sources 不对称',
        detail: 'LLM 用展开后的父全文；响应 sources 仍用原始子块命中 + parentExcerpt，便于对照切分。',
      },
    ],
  },
  backend: [
    {
      label: '建索引 — buildParentChildChunks',
      language: 'java',
      code: `// 父块：复用语义切，保证章节边界完整
List<Document> parents = semanticSplitter.apply(sourceDocs);
for (Document parent : parents) {
  parentSeq++;
  String parentText = parent.getText() == null ? "" : parent.getText();
  // parentId 绑定序号+来源，供检索后按父去重
  String parentId = "p-" + parentSeq + "-" + source;
  // 子块：短切片，便于 Embedding 命中；childSize 来自配置
  List<String> parts = SemanticMarkdownSplitter.hardSplit(parentText, childSize);
  if (parts.isEmpty()) continue; // 空父段不入库，避免脏向量
  for (String part : parts) {
    meta.put("chunkRole", "child");
    meta.put("parentId", parentId);
    // 每子块复制整段父文：展开时无需二次查库（演示取舍，非生产最优）
    meta.put("parentText", parentText);
    meta.put("chunkIndex", chunkIndex++);
    children.add(Document.builder().text(part).metadata(meta).build());
  }
}
// 返回值只有 children：父块从不单独 Embedding`,
    },
    {
      label: '展开 — expandParents',
      language: 'java',
      code: `// 关闭 expand-parent：生成也只用子块，便于对照「展开开/关」
if (!ragSettings.chunking().expandParent()) return hits;

Map<String, Document> parents = new LinkedHashMap<>(); // 保序去重
for (Document hit : hits) {
  if ("child".equals(role) && parentText != null) {
    // 缺 parentId 时回退 hit.id，仍能去重展开
    String parentId = meta.get("parentId") == null ? hit.getId()
        : String.valueOf(meta.get("parentId"));
    if (!parents.containsKey(parentId)) {
      parentMeta.put("chunkRole", "parent"); // 标记已展开，避免再次当子块处理
      parents.put(parentId, Document.builder()
          .id(parentId)
          .text(String.valueOf(parentText)) // LLM 上下文用父全文
          .metadata(parentMeta)
          .build());
    }
  } else {
    passthrough.add(hit); // 非父子策略命中原样保留
  }
}
// 父在前、透传在后；sources 仍用原始 hits，不在这里改`,
    },
    {
      label: '生成前展开 — answerFromHits',
      language: 'java',
      code: `List<Document> contextDocs = expandParents(hits); // 上下文用父
String context = buildContext(contextDocs);
// sources 仍 toSources(hits)：UI 看子块 + parentExcerpt`,
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
    {
      label: '三路对照（含 parentChild）',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query/compare-chunking\`, {
  question, provider,
})
// 响应：token / semantic / parentChild`,
    },
  ],
}
