import type { SampleGuideData } from './types'

/** 语义分块样例讲解：token vs 结构感知切分，核心切割逻辑行级对照。 */
export const semanticChunkGuide: SampleGuideData = {
  title: '语义分块',
  concepts: [
    'token 切：固定长度，实现简单，但容易在章节中间切断，检索命中片段缺上下文。',
    'semantic（本仓）：按 Markdown H1–H3 与空行切段，再软合并到 chunk-size；确定性、无 LLM。',
    '两套索引用不同 corpus 并存（默认 demo vs demo-semantic）；默认 query 仍走 token，保证旧样例。',
    '与 Hybrid / HyDE 正交：可叠加 retrievalMode / queryExpansion，分块策略只决定读哪套语料。',
    'Java 超长段会 hardSplit；Python 对照实现整段保留、不做硬切——刻意差异，便于对照。',
  ],
  logic: {
    title: '切割管线',
    steps: [
      {
        title: '标题切（toSegments 第一步）',
        detail:
          '用 lookahead 在 #{1,3} 标题行前切开：标题留在下一块开头，文首无标题时可能产生空前缀并丢弃。',
      },
      {
        title: '空行切段落',
        detail:
          '每块去掉标题行后，按连续空行切段落；单换行留在段内。空节仍写入「## 标题」锚点，避免标题丢失。',
      },
      {
        title: '首段注入标题前缀',
        detail:
          '同节仅第一段正文前加「## heading」与空行，后续段共享 metadata.heading、正文不重复标题，便于 Embedding。',
      },
      {
        title: '软合并 softMerge',
        detail:
          '同标题下打包到 targetSize（段间多 2 字符空行）；换标题必须先 flush，防止跨节语义混装。',
      },
      {
        title: '超长硬切 hardSplit（仅 Java）',
        detail:
          '单段已超标则不与邻段粘；在句号或换行处断，断点须 > max/3，否则硬切到 max，避免过碎。',
      },
      {
        title: 'ingest 隔离与 compare',
        detail:
          '按 corpus 删旧再写；compare-chunking 对两套语料只比 sources，默认不跑双重 LLM。',
      },
    ],
  },
  backend: [
    {
      label: '结构切 — toSegments',
      language: 'java',
      code: `// (?m) 让 ^ 匹配行首；(?=…) 零宽断言：在标题前切开，标题留在下一块
String[] blocks = text.split("(?m)(?=^#{1,3}\\\\s+)");
for (String block : blocks) {
  if (block.isBlank()) continue; // 文首无标题时可能空前缀，丢弃
  String trimmed = block.trim();
  String heading = null;
  String body = trimmed;
  // (?s) 让 . 跨行：判定本块是否以 H1–H3 起头
  if (trimmed.matches("(?s)^#{1,3}\\\\s+.+")) {
    int nl = trimmed.indexOf('\\n');
    if (nl < 0) {
      // 整块只有标题行 → 无法再切正文，整段入库
      heading = trimmed.replaceFirst("^#{1,3}\\\\s+", "").trim();
      segments.add(new Segment(heading, trimmed));
      continue;
    }
    // 标题与正文分离：首行抽 heading，余下再按空行切
    heading = trimmed.substring(0, nl).replaceFirst("^#{1,3}\\\\s+", "").trim();
    body = trimmed.substring(nl + 1).trim();
  }
  if (body.isEmpty()) {
    // 空节仍留可检索锚点，避免标题在索引中消失
    if (heading != null) segments.add(new Segment(heading, "## " + heading));
    continue;
  }
  // 连续空行（≥2 个换行）才切段；单换行视为段内换行
  String[] paras = body.split("\\n{2,}");
  boolean first = true;
  for (String para : paras) {
    String p = para.trim();
    if (p.isEmpty()) continue;
    // 仅首段注入 ## 标题前缀，同节后续段不重复标题
    String content = (first && heading != null)
        ? ("## " + heading + "\\n\\n" + p) : p;
    first = false;
    segments.add(new Segment(heading, content));
  }
}
// 无标题/空行结构时整文一块，避免吞掉内容
if (segments.isEmpty()) segments.add(new Segment(null, text.trim()));`,
    },
    {
      label: '软合并 — softMerge',
      language: 'java',
      code: `for (Segment seg : segments) {
  String piece = seg.body();
  if (piece.length() > targetSize) {
    flush(merged, currentHeading, buf); // 先吐出缓冲，超长段不与邻段粘
    for (String part : hardSplit(piece, targetSize)) {
      merged.add(new Segment(seg.heading(), part));
    }
    currentHeading = seg.heading();
    continue;
  }
  // null 与非 null 也算换节：跨标题必须断块
  boolean headingChanged = (currentHeading == null) != (seg.heading() == null)
      || (currentHeading != null && !currentHeading.equals(seg.heading()));
  if (headingChanged && buf.length() > 0) flush(merged, currentHeading, buf);

  if (buf.length() == 0) {
    currentHeading = seg.heading();
    buf.append(piece);
  } else if (buf.length() + 2 + piece.length() <= targetSize) {
    // +2 是段间空行；能装则软粘
    buf.append("\\n\\n").append(piece);
  } else {
    flush(merged, currentHeading, buf); // 装不下：断在段边界，新开缓冲
    currentHeading = seg.heading();
    buf.append(piece);
  }
}
flush(merged, currentHeading, buf); // 收尾，避免最后一包丢失`,
    },
    {
      label: '超长硬切 — hardSplit',
      language: 'java',
      code: `int start = 0;
while (start < text.length()) {
  // 先按 max 字符取候选右界
  int end = Math.min(start + max, text.length());
  if (end < text.length()) {
    // 优先在句号或换行断，减少切断半句
    int breakAt = Math.max(
        text.lastIndexOf('。', end), text.lastIndexOf('\\n', end));
    // 断点太靠左则放弃，避免切出过短碎片；硬切到 max
    if (breakAt > start + max / 3) end = breakAt + 1;
  }
  String slice = text.substring(start, end).trim();
  if (!slice.isBlank()) parts.add(slice);
  start = end; // 无重叠窗口推进
}`,
    },
    {
      label: '入库选路 — ingestOne',
      language: 'java',
      code: `// 按 corpus 删旧再写，双索引隔离互不覆盖
String corpus = corpusFor(strategy);
List<Document> chunks = strategy == ChunkingStrategy.semantic
    ? semanticSplitter.apply(sourceDocs)
    : splitter.apply(sourceDocs);
chunk.getMetadata().put("corpus", corpus);
chunk.getMetadata().put("chunking", strategy.name());`,
    },
  ],
  frontend: [
    {
      label: '对照两路命中',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query/compare-chunking\`, {
  question, provider,
})`,
    },
    {
      label: '单路指定分块语料',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/rag/query\`, {
  question, provider, chunkingStrategy: 'semantic', // 或 'token'
})`,
    },
  ],
}
