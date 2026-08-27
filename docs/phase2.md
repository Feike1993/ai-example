# 第二期学习路径：MCP + RAG

第一期覆盖 Chat / 结构化输出 / Tool Calling / Agent Loop。第二期在此之上加两块：**MCP**（工具标准化接入）与 **RAG**（检索增强）。

上下文工程 / 多 Agent 见 [第三期](phase3.md)。刻意不做清单见 [backlog](backlog.md)。

## 建议顺序

1. **MCP**（[samples/05-mcp.md](samples/05-mcp.md)）
   - MCP vs Function Calling vs Agent
   - Host / Client / Server；Streamable HTTP vs stdio
   - Java：同进程 MCP Server 暴露工具；样例聊天复用其 ToolCallbackProvider（关闭自连 Client 避免启动鸡生蛋）
   - Python：stdio Server + Client 对照
2. **RAG**（[samples/06-rag.md](samples/06-rag.md)）
   - 离线索引（分块 → Embedding → pgvector）与在线检索生成
   - Embedding 与 Chat Provider 分离（本仓 Embedding 固定 DashScope）
   - Java：ingest / query / SSE；Python：内存向量对照

## 怎么跑（摘要）

```bash
# 仅 RAG 需要
docker compose up -d

cd java && ./gradlew bootRun
cd frontend && pnpm install && pnpm dev

# Python 对照
cd python && uv sync --group dev
uv run python -m ai_example.samples.mcp_client
uv run python -m ai_example.samples.rag
```

前端侧栏新增 **MCP**、**RAG** 两个 Tab。
