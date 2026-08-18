# 第二期预告（未实现）

第一期刻意不引入数据库、向量库和 MCP，避免样例变成又一个业务系统。下面是下一期要学的方向。

## MCP

- Spring AI 2.0 官方 MCP Java SDK（Streamable HTTP 为默认传输）
- Python MCP SDK 对照
- MCP vs Function Calling vs Agent；stdio vs 远程

## RAG

- pgvector + 文本分块 + Embedding
- 查询改写、自适应 topK、SSE 流式回答

## 上下文工程

- 窗口裁剪 vs 增量摘要
- Token 预算、Lost in the Middle

## 多 Agent

- Orchestrator-Subagent
- LangGraph 多节点图
- 评价 / 可观测（轨迹、步数、工具失败率）
