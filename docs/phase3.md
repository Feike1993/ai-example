# 第三期学习路径：上下文工程 + 多 Agent

第一、二期覆盖 Chat / 结构化 / Tools / Agent / MCP / RAG。第三期在此之上加两块：**上下文工程**（会话记忆与预算）与 **多 Agent**（Orchestrator–Subagent）。

建议顺序：先上下文，再多 Agent。

## 建议顺序

1. **上下文工程**（[samples/07-context.md](samples/07-context.md)）
   - 模型无跨请求记忆；「记得」= 你塞回窗口的内容
   - trim（窗口裁剪）vs summarize（增量摘要）
   - Token 预算直觉、Lost in the Middle
2. **多 Agent**（[samples/08-multi-agent.md](samples/08-multi-agent.md)）
   - Orchestrator 拆任务 → 专员（工具 / 执笔）→ 汇总
   - 显式交接 vs 单 Agent 堆全部工具
   - 轨迹里看步数、谁失败

## 怎么跑（摘要）

```bash
cd java && ./gradlew bootRun
cd frontend && pnpm install && pnpm dev

# Python 对照
cd python && uv sync --group dev
uv run python -m ai_example.samples.context_memory
uv run python -m ai_example.samples.multi_agent
```

前端侧栏新增 **上下文**、**多 Agent** 两个 Tab。

刻意不做的后续候选见 [backlog.md](backlog.md)。
