# Python 对照样例

与 Java 四个概念一一对应，默认读仓库根目录 `.env`。

```bash
uv sync --group dev
uv run python -m ai_example.samples.chat
uv run python -m ai_example.samples.structured
uv run python -m ai_example.samples.tools
uv run python -m ai_example.samples.react_agent
uv run pytest
```

LangGraph 的 `create_react_agent` 对应 Java 的 `ReactAgentLoop` / Spring AI 自动 tool-calling。
