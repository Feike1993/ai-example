# Python 对照样例

与 Java 概念一一对应。它是独立进程的轻量对照实现，不连接 Java 的数据库模型设置，
仍读取仓库根目录 `.env` 中标注为“仅 Python”的 `AI_PROVIDER` / `PROVIDER_*` 变量。

```bash
uv sync --group dev

# 第一期
uv run python -m ai_example.samples.chat
uv run python -m ai_example.samples.structured
uv run python -m ai_example.samples.tools
uv run python -m ai_example.samples.react_agent

# 第二期
uv run python -m ai_example.samples.mcp_client
uv run python -m ai_example.samples.rag

# 第三期
uv run python -m ai_example.samples.context_memory
uv run python -m ai_example.samples.multi_agent

uv run pytest
```

刻意不做清单见仓库 [`docs/backlog.md`](../docs/backlog.md)。
