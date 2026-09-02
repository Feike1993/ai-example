# 第六期学习路径：MCP 远端拆分 + 完整 HyDE

第五期覆盖持久会话与长期记忆。第六期做两项与生产形态更接近的进阶：

1. **MCP 远端拆分**（[samples/13-mcp-remote.md](samples/13-mcp-remote.md)）— Server 独立进程，主应用 Client 真连
2. **完整 HyDE**（[samples/14-hyde.md](samples/14-hyde.md)）— 假想文档 Embedding 检索，对照 none / rewrite

建议顺序：先起 `mcp-server` 再跑主应用 MCP Tab；HyDE 需 Docker pgvector + Embedding Key。

## 建议顺序

1. **MCP 远端**
   - `app.ai.mcp.mode=remote|inprocess`（默认 remote）
   - remote：先 `cd mcp-server && ./gradlew bootRun`（8081），再起主应用
2. **HyDE**
   - `queryExpansion: none|rewrite|hyde`
   - compare-expansion 对照三套 sources

## 怎么跑（摘要）

```bash
# 终端 1：MCP Server
cd mcp-server && ./gradlew bootRun

# 终端 2：主应用
docker compose up -d
cd java && ./gradlew bootRun
cd frontend && pnpm install && pnpm dev

curl -s http://localhost:8080/ai-example/mcp/tools
curl -s http://localhost:8080/ai-example/mcp/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？","provider":"deepseek"}'

curl -s -X POST http://localhost:8080/ai-example/rag/ingest
curl -s http://localhost:8080/ai-example/rag/query/compare-expansion \
  -H 'Content-Type: application/json' \
  -d '{"question":"本项目第一期学了什么？","provider":"deepseek"}'
```

Python 对照：

```bash
cd python && uv sync --group dev
uv run python -m ai_example.samples.mcp_client_http
uv run python -m ai_example.samples.hyde_rag
```

刻意不做（第六期）：语义分块、Redis。MCP HTTP Bearer 鉴权见第九期 [19-mcp-bearer.md](samples/19-mcp-bearer.md) / [phase9.md](phase9.md)。
