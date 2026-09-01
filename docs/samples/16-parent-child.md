# 16 父子文档

## 概念

| 角色 | 用途 |
| --- | --- |
| **子块 child** | 小段 Embedding / 相似度检索 |
| **父块 parent** | 章节级全文；命中子块后拼进生成上下文 |

- ingest `strategy=parent_child` → corpus `ai-example-demo-parent`
- 子块 metadata：`chunkRole=child`、`parentId`、`parentText`、`chunkIndex`
- 检索后默认 `expand-parent=true`：去重父块再 `buildContext`
- `compare-chunking` 第三路 `parentChild`

## 怎么跑

```bash
curl -s -X POST http://localhost:8080/ai-example/rag/ingest \
  -H 'Content-Type: application/json' \
  -d '{"strategy":"all"}' | jq .

curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 是什么？","chunkingStrategy":"parent_child","provider":"deepseek"}' \
  | jq '.chunkingStrategy,.sources[0].chunkRole,.sources[0].parentExcerpt,.answer'

curl -s http://localhost:8080/ai-example/rag/query/compare-chunking \
  -H 'Content-Type: application/json' \
  -d '{"question":"一期学了什么？","provider":"deepseek"}' \
  | jq '.token.sources|length,.semantic.sources|length,.parentChild.sources|length'
```

## 学什么

- 召回粒度（子）与生成上下文粒度（父）解耦
- 同章多子块命中时父块去重，避免重复塞 prompt

## 刻意不做

LLM 切边界、异步索引 — [backlog](../backlog.md)。
