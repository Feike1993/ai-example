# 14 完整 HyDE

## 概念

| 策略 | 做法 |
| --- | --- |
| `none` | 用用户原问题做向量（或 hybrid）检索 |
| `rewrite` | 第四期最小改写：口语 → 检索友好短句，再检索 |
| `hyde` | **Hypothetical Document Embeddings**：Chat 生成一段「假想知识库段落」→ Embedding 该段落检索；答案仍 grounded 在真实 chunk |

HyDE 解决「问题表述」与「文档表述」鸿沟；**假想文档只用于检索，不得当作 sources 返回给用户当事实**。

可选：假想段落向量路与「原问题向量路」做 RRF（`app.ai.rag.hyde.fuse-with-original=true`，默认开）。

## 怎么跑

```bash
docker compose up -d
cd java && ./gradlew bootRun
curl -s -X POST http://localhost:8080/ai-example/rag/ingest
```

单路 HyDE：

```bash
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"一期都学了哪些能力？","queryExpansion":"hyde","provider":"deepseek"}' \
  | jq '.queryExpansion,.hypotheticalDocument,.sources'
```

三套对照（默认只比 sources，不三次生成，控成本）：

```bash
curl -s http://localhost:8080/ai-example/rag/query/compare-expansion \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 是什么？","provider":"deepseek"}' \
  | jq '.none.sources,.rewrite.sources,.hyde.sources,.hyde.hypotheticalDocument'
```

## 学什么

- rewrite vs HyDE：改写查询 vs 假想文档
- sources 必须来自真实语料
- 与 Hybrid（关键词 RRF）正交，可叠加 `retrievalMode`

## 刻意不做

多轮改写链、语义分块 — 见 [backlog](../backlog.md)。
