# 23 RAG 强制 citation

## 概念

A3 只处理「检索为空」；本样例要求**有 hits 时答案必须带可校验引用**：

1. `citationMode=required` 时用结构化输出：`{ answer, citations:[{sourceId, quote}] }`
2. Prompt 侧用短别名 `C1..Cn` + allowlist，避免模型把文件名当成 sourceId
3. **后校验前归一化**：别名 / 唯一文件名 / 序号 → 真实 `sources[].id`；再严格校验；`citations` 不能为空
4. 失败 → `citationValid=false`，返回固定拒答（与 `retrievalEmpty` 字段区分）

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

自动补全空 citation、同文件多 chunk 时猜测文件名、多租户 ACL、流式 citation — 见 [backlog](../backlog.md)。
