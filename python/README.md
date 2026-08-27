# Python 对照样例

与 Java 概念一一对应，默认读仓库根目录 `.env`（`AI_PROVIDER` 默认 `deepseek`）。

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
