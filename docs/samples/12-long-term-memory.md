# 12 长期记忆

## 概念

| | 会话窗口（第三 / 五期 context） | 长期记忆（本样例） |
| --- | --- | --- |
| 寿命 | 一轮到数轮对话 | 跨会话、按用户召回 |
| 存储 | 消息列表（可持久化） | pgvector Embedding |
| 隔离 | `sessionId` | `corpus=long-term-memory` + `userId` |

与 RAG 演示语料（`corpus=ai-example-demo`）**不要混用**。

流程：

1. **remember** — 把事实写入向量库
2. **recall** — 按问题相似度召回
3. **chat** — 召回结果拼进 prompt 再生成

## 怎么跑

需要 Docker pgvector + DashScope Embedding Key（与 RAG 相同，`app.ai.rag.enabled=true`）。

```bash
docker compose up -d
cd java && ./gradlew bootRun
```

```bash
curl -s -X POST http://localhost:8080/ai-example/memory/remember \
  -H 'Content-Type: application/json' \
  -d '{"text":"用户名叫小明，喜欢北京烤鸭","userId":"demo"}'

curl -s -X POST http://localhost:8080/ai-example/memory/recall \
  -H 'Content-Type: application/json' \
  -d '{"query":"喜欢吃什么","userId":"demo"}' | jq '.sources'

curl -s -X POST http://localhost:8080/ai-example/memory/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"根据记忆，我喜欢吃什么？","userId":"demo"}' | jq '.answer,.sources'

curl -s -X DELETE 'http://localhost:8080/ai-example/memory?userId=demo'
```

Python 对照（内存向量）：

```bash
cd python && uv run python -m ai_example.samples.long_term_memory
```

## 学什么

- 写入时机与召回过滤（userId / corpus）
- 空召回时不要编造
- 回答时可轻度修正明显笔误（如「生活中杭州」→「生活在杭州」）；sources / 向量库仍为原文。若要改正文，更正后再 Remember（相似合并会覆盖）
- 长期记忆 ≠ 知识库 RAG（个人事实 vs 文档语料）
- **重复写入**：同 userId 下完全相同文本不会再次入库；语义相似（默认 score≥0.92）则删旧写新；召回结果按文本去重

## 已有重复数据怎么办

演示时多次点 Remember 若曾产生重复向量，可先 **清空** 该 userId 的记忆再重新 remember。新写入路径会做精确跳过与相似合并。

## 刻意不做

自动从对话抽取记忆的 Agent、多租户权限、异步索引 — 见 [backlog](../backlog.md)。

记忆辅助的 **查询改写**（`memory_rewrite`）与 RAG/记忆双路对照不在本样例：见 [第十二期](../phase12.md) / [24](24-memory-informed-rewrite.md) / [25](25-rag-vs-memory-compare.md)。
