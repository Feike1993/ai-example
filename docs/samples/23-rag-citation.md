# 23 RAG 强制 citation

## 概念

A3 只处理「检索为空」；本样例要求**有 hits 时答案必须带可校验引用**：

1. `citationMode=required` 时用结构化输出：`{ answer, citations:[{sourceId, quote}] }`
2. **后校验**：每条 `sourceId` ∈ 本次 `sources[].id`；`citations` 不能为空
3. 失败 → `citationValid=false`，返回固定拒答（与 `retrievalEmpty` 字段区分）

默认 `citationMode=none` 保持样例 06 自由文本行为。

## 怎么跑

```bash
# 先 ingest
curl -s http://localhost:8080/ai-example/rag/ingest -H 'Content-Type: application/json' -d '{}'

curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"本项目第一期学了什么？","citationMode":"required"}' \
  | jq '{citationMode,citationValid,citations,answer:.answer[0:120]}'
```

Playground：进阶 Tab **RagCitation**。

## 刻意不做

自动改写补 citation、多租户 ACL、流式 citation — 见 [backlog](../backlog.md)。
