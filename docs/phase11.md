# 第十一期学习路径：护栏与引用

第十期覆盖可观测流式。第十一期补答前/答后约束与「有出处才答」：

| 样例 | 焦点 |
| --- | --- |
| [22 输出护栏](samples/22-output-guardrail.md) | 输入/输出敏感词 + 可选结构校验；`checks` 可观测 |
| [23 RAG 强制 citation](samples/23-rag-citation.md) | 结构化引用；`sourceId` 必须落在检索 hits |

## 建议顺序

1. `POST /guardrail/chat` 试输入命中拒答与输出词表
2. 开 `requireStructured` 看结构失败短路
3. `POST /rag/query` 带 `citationMode=required`，核对 `citations` / `citationValid`
4. Playground 进阶：**Guardrail** → **RagCitation**

## 怎么跑（摘要）

```bash
cd java && ./gradlew bootRun

curl -s http://localhost:8080/ai-example/guardrail/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"你好"}' | jq .

curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"本项目第一期学了什么？","citationMode":"required"}' | jq '.citationValid,.citations,.answer'
```

刻意不做：第三方 Moderation、多租户 ACL、流式逐 token 拦截 — 见 [backlog.md](backlog.md)。
