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
uv run python -m ai_example.samples.mcp_client   # stdio MCP Server + Client
uv run python -m ai_example.samples.rag          # 内存向量 RAG（需 DashScope Embedding Key）

uv run pytest
```

- LangGraph 的 `create_react_agent` 对应 Java 的 `ReactAgentLoop` / Spring AI 自动 tool-calling
- MCP：`mcp_server`（stdio）+ `mcp_client`（发现工具后交给 OpenAI）
- RAG：内存余弦相似度讲清算法；生产用 Java 侧 pgvector
