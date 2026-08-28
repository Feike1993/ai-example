# 09 Hybrid RAG

## 概念

纯向量检索擅长语义相似，但可能漏掉**关键词精确匹配**（如产品代号 `MCP`、`pgvector`）。Hybrid 在向量路之外增加 PostgreSQL 全文检索路，用 **RRF（Reciprocal Rank Fusion）** 融合排名：

\[
\text{score}(d) = \sum_i \frac{1}{k + \text{rank}_i(d)}
\]

本仓 `k` 默认 60（`app.ai.rag.hybrid.rrf-k`）。

## 与二期的关系

- 默认 `retrievalMode=vector`，行为与二期一致
- ingest / Embedding / pgvector 表结构不变；ingest 后额外创建 content 的 GIN 全文索引
- sources 增加 `vectorRank` / `keywordRank` / `rrfScore` 便于对照

## 怎么跑

```bash
docker compose up -d
cd java && ./gradlew bootRun
curl -s -X POST http://localhost:8080/ai-example/rag/ingest
```

Hybrid 单路：

```bash
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 和 Function Calling 区别？","retrievalMode":"hybrid","provider":"deepseek"}'
```

并排对比：

```bash
curl -s http://localhost:8080/ai-example/rag/query/compare \
  -H 'Content-Type: application/json' \
  -d '{"question":"RAG 是什么？","provider":"deepseek"}' | jq '.vector.sources, .hybrid.sources'
```

配置：

```yaml
app.ai.rag.hybrid:
  enabled: true
  rrf-k: 60
  keyword-top-k: 4
  rewrite-query-enabled: false   # 或请求体 rewriteQuery: true
```

Python 对照（内存向量 + 简单 BM25 + RRF，不依赖 Docker）：

```bash
cd python && uv run python -m ai_example.samples.hybrid_rag
```

## 学什么

- 向量 vs 关键词各自漏什么
- RRF 比手工加权更稳
- UI / JSON 里看 rank 字段理解「为何 hybrid 多召回了这条」

## 刻意不做

混合检索以外的 HyDE、语义分块、知识库产品 — 见 [backlog.md](../backlog.md) 与 [phase5.md](../phase5.md)。
