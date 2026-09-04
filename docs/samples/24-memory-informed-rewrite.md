# 24 记忆辅助改写

## 概念

普通 `rewrite` 只看用户问题。`memory_rewrite` 先按 `userId` **recall** 个人事实，把摘录作为改写先验，再去检索 **RAG 演示语料**。

| 字段 | 含义 |
| --- | --- |
| `memoryHints` | 记忆召回（corpus=`long-term-memory`）；**不进** `sources` |
| `rewrittenQuery` | 带记忆先验后的检索短句 |
| `sources` | 仅 RAG KB hits |

语料继续隔离：个人事实 ≠ 知识库文档。无记忆时回退普通 rewrite。

## 怎么跑

```bash
# 先 remember + ingest
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{
    "question":"一期都学了哪些？",
    "queryExpansion":"memory_rewrite",
    "userId":"demo",
    "memoryTopK":4
  }' | jq '{rewrittenQuery,memoryHints,retrievalEmpty,answer:.answer[0:100]}'

# 三路对照（默认只比 sources）
curl -s http://localhost:8080/ai-example/rag/query/compare-memory-rewrite \
  -H 'Content-Type: application/json' \
  -d '{"question":"一期都学了哪些？","userId":"demo"}' | jq .
```

Playground：进阶 **MemoryInformedRag**。

Python：`uv run python -m ai_example.samples.memory_informed_rag`（假 recall/rewrite/hits，无 Key）。

## 与 HyDE / 普通 rewrite 的差别

| 策略 | 先验 | 假想正文进 sources？ |
| --- | --- | --- |
| `none` | 无 | — |
| `rewrite` | 仅问题 | — |
| `hyde` | 假想文档 Embedding | **否**（假想仅检索） |
| `memory_rewrite` | 个人记忆 hints | **否**（hints 仅改写） |

## 刻意不做

把记忆向量写入 RAG corpus、HyDE+记忆混合 — 见 [backlog](../backlog.md)。
