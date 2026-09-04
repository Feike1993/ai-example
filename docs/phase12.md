# 第十二期学习路径：记忆×检索闭环

第十一期覆盖护栏与引用。第十二期把长期记忆接到 RAG 查询链，并对照「个人事实 vs 知识库」：

| 样例 | 焦点 |
| --- | --- |
| [24 记忆辅助改写](samples/24-memory-informed-rewrite.md) | `queryExpansion=memory_rewrite`：recall → 改写 → 检索 |
| [25 RAG vs 记忆对照](samples/25-rag-vs-memory-compare.md) | 同问双路 sources / 答案，看清语料边界 |

## 核心边界

| 通道 | corpus | 进响应哪里 |
| --- | --- | --- |
| 长期记忆 | `long-term-memory` + `userId` | `memoryHints`（改写先验）或 compare 的 `memory` 路 |
| 知识库 RAG | 演示 corpus | `sources` / compare 的 `rag` 路 |

**禁止**：把记忆向量写入 RAG corpus；`memoryHints` 的 id **不得**出现在 RAG `sources`。

## 建议顺序

1. `remember` 若干个人事实 + `ingest` 知识库
2. `POST /rag/query` 带 `queryExpansion=memory_rewrite`，看 `memoryHints` 与 `rewrittenQuery`
3. `POST /rag/query/compare-memory-rewrite` 三路只比 sources
4. `POST /rag/query/compare-memory` 并排 RAG / Memory（可 `generateAnswers=false`）
5. Playground：**MemoryInformedRag** → **RagMemoryCompare**

## 怎么跑（摘要）

```bash
cd java && ./gradlew bootRun

curl -s http://localhost:8080/ai-example/memory/remember \
  -H 'Content-Type: application/json' \
  -d '{"text":"用户在学 ai-example 第一期 Chat 与 Agent","userId":"demo"}'

curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"一期都学了哪些？","queryExpansion":"memory_rewrite","userId":"demo"}' \
  | jq '{queryExpansion,rewrittenQuery,memoryHints,sources:(.sources|length),answer:.answer[0:80]}'

curl -s http://localhost:8080/ai-example/rag/query/compare-memory \
  -H 'Content-Type: application/json' \
  -d '{"question":"我喜欢吃什么？一期 RAG 是什么？","userId":"demo","generateAnswers":false}' \
  | jq '{ragEmpty:.rag.retrievalEmpty,memEmpty:.memory.retrievalEmpty}'
```

Python 假链路（无 Key）：

```bash
cd python
uv run python -m ai_example.samples.memory_informed_rag
uv run python -m ai_example.samples.rag_memory_compare
uv run pytest tests/test_memory_informed_rag.py tests/test_rag_memory_compare.py -q
```

## 前端 / 索引

- Playground 进阶样例 index 22–23；`AdvancedPhaseId` 含 `12`
- 根索引 `GET /` → `advanced.phase=12`，含 `memoryInformedRag` / `ragMemoryCompare`

刻意不做：记忆并入 RAG corpus、Redis、静默 extract→rewrite、自动融合双路答案 — 见 [backlog.md](backlog.md)。
