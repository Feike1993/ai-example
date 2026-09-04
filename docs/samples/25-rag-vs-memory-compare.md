# 25 RAG vs 记忆对照

## 概念

同一问题并排两路，看清边界：

| 路 | 语料 | 适合回答 |
| --- | --- | --- |
| `rag` | 演示知识库 | 「RAG / 一期能力」等文档问题 |
| `memory` | `long-term-memory` + userId | 「我喜欢吃什么」等个人事实 |

`POST /rag/query/compare-memory`：默认 `generateAnswers=true`；设 `false` 只比 sources（控成本）。

复合问题（一半个人、一半文档）常出现一侧 empty、一侧命中——这正是对照的教学点。

注意：`retrievalEmpty=false` 只表示检索到了 sources，不等于模型答全了题。旧提示在「上下文盖不住个人偏好」时，容易整题说「不知道」；当前 grounded 提示要求：**能支撑的子问要答，不能支撑的子问标明不知道**。因此对默认复合问，预期大致是：

| 路 | 预期 |
| --- | --- |
| RAG | 可答文档半边（一期能力 / RAG 概念；一期文档本身不含「一期 RAG」专名，宜据 sources 如实说明）；「喜欢吃什么」标明不知道 |
| Memory | 可答个人半边（如北京烤鸭）；文档半边标明不知道 |

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/rag/query/compare-memory \
  -H 'Content-Type: application/json' \
  -d '{
    "question":"我喜欢吃什么？一期 RAG 是什么？",
    "userId":"demo",
    "provider":"deepseek",
    "generateAnswers":true
  }' | jq '{ragEmpty:.rag.retrievalEmpty,memEmpty:.memory.retrievalEmpty,rag:.rag.answer[0:60],mem:.memory.answer[0:60]}'
```

Playground：进阶 **RagMemoryCompare**。

Python：`uv run python -m ai_example.samples.rag_memory_compare`。

## 刻意不做

自动融合两路答案、评测看板 — 见 [backlog](../backlog.md)。
