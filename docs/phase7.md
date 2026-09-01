# 第七期学习路径：语义分块 + 父子文档

第六期覆盖 MCP 远端与 HyDE。第七期做 RAG **分块策略**进阶，拆成两个分支先后交付：

| 分支 | 样例 | 状态 |
| --- | --- | --- |
| `phase7a` | [15 语义分块](samples/15-semantic-chunk.md) | **本分支交付** |
| `phase7b` | [16 父子文档](samples/16-parent-child.md) | 待 7a 合入后再做 |

## 建议顺序（7a）

1. `POST /rag/ingest` 带 `strategy=all`（或分别 token / semantic）
2. `POST /rag/query/compare-chunking` 对照两套 sources
3. Playground 进阶 Tab **SemanticChunk**

## 怎么跑（摘要）

```bash
docker compose up -d
cd java && ./gradlew bootRun

curl -s -X POST http://localhost:8080/ai-example/rag/ingest \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"all"}'

curl -s http://localhost:8080/ai-example/rag/query/compare-chunking \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 是什么？","provider":"deepseek"}'
```

Python：

```bash
cd python && uv sync --group dev
uv run python -m ai_example.samples.semantic_chunk
```

刻意不做（本仓）：LLM 切边界、异步索引 — 见 [backlog.md](backlog.md)。父子文档见 7b / 样例 16。
