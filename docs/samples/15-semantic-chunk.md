# 15 语义分块

## 概念

| 策略 | 做法 | corpus |
| --- | --- | --- |
| `token` | `TokenTextSplitter` 固定目标长度（二期默认） | `ai-example-demo` |
| `semantic` | 按 Markdown 标题 / 空行切段再软合并到 `chunk-size`（**无 LLM**） | `ai-example-demo-semantic` |

同一 `vector_store` 表，靠 `corpus` 元数据隔离；默认 query 仍走 token corpus，保证旧样例不变。

## 怎么跑

```bash
docker compose up -d
cd java && ./gradlew bootRun

# 重建两套索引
curl -s -X POST http://localhost:8080/ai-example/rag/ingest \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"all"}' | jq .

# 单路语义语料
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"一期学了什么？","chunkingStrategy":"semantic","provider":"deepseek"}' \
  | jq '.chunkingStrategy,.sources'

# 对照 token vs semantic（默认只比 sources）
curl -s http://localhost:8080/ai-example/rag/query/compare-chunking \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 和 Function Calling 区别？","provider":"deepseek"}' \
  | jq '.token.sources,.semantic.sources'
```

## 学什么

- 切块边界决定 Embedding 的语义单元
- 结构切更贴章节，但可能漏跨段语义
- 与 Hybrid / HyDE **正交**：可叠加 `retrievalMode` / `queryExpansion`

## 刻意不做

LLM 语义切边界、父子文档（见 [16](16-parent-child.md) / 7b）— [backlog](../backlog.md)。
