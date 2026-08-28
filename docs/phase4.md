# 第四期学习路径：Hybrid RAG + Agent 评测

**v0.2.0 baseline** 发版后进入进阶第四期。本仓只做两项与现有代码衔接最紧的进阶：**检索质量**与**可观测/回归**。

建议顺序：

1. **Hybrid RAG**（[samples/09-hybrid-rag.md](samples/09-hybrid-rag.md)）
2. **Agent 评测**（[samples/10-eval.md](samples/10-eval.md)）

## Hybrid RAG

- 在二期纯向量之上增量：PostgreSQL `plainto_tsquery` 关键词路 + **RRF** 融合
- `POST /rag/query` 增加 `retrievalMode: vector|hybrid`（默认 vector，保持二期行为）
- `POST /rag/query/compare` 同一问题并排返回两套 sources + answer
- 可选 `rewriteQuery`：一次 plain Chat 改写检索问句（非 HyDE）

## Agent 评测

- `classpath:eval/golden/*.json` golden 用例
- `POST /eval/run` → 通过率 / 工具失败率 / 步数 / 耗时 / usage 汇总
- target：`tools` | `agentReact` | `rag` | `multiagent`

## 怎么跑（摘要）

```bash
docker compose up -d
cd java && ./gradlew bootRun
cd frontend && pnpm install && pnpm dev

curl -s -X POST http://localhost:8080/ai-example/rag/ingest
curl -s http://localhost:8080/ai-example/rag/query/compare \
  -H 'Content-Type: application/json' \
  -d '{"question":"MCP 是什么？","provider":"deepseek","retrievalMode":"hybrid"}'

curl -s -X POST http://localhost:8080/ai-example/eval/run \
  -H 'Content-Type: application/json' \
  -d '{"provider":"deepseek"}'
```

Python 对照：

```bash
cd python && uv sync --group dev
uv run python -m ai_example.samples.hybrid_rag
uv run python -m ai_example.samples.eval_runner
```

第五期占位见 [phase5.md](phase5.md)。刻意不做总表见 [backlog.md](backlog.md)。
