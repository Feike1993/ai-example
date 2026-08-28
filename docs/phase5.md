# 第五期占位（未实现）

第四期交付 Hybrid RAG 与 golden 评测后，下列主题仍留在 backlog，仅作文档占位，避免 scope 膨胀：

## 基础设施

- Redis / DB **持久会话**（升级第三期进程内 context）
- MCP **独立进程** Server + Client 真连远端 Streamable HTTP

## RAG / 记忆进阶

- **长期记忆**写入 pgvector 独立 collection
- 语义分块 / 父子文档
- 完整 **HyDE** / 多轮查询改写链
- 异步索引管道（如 Redis Stream）

## 多 Agent / 可观测

- 分布式 / 多进程 Agent、消息总线
- 评测**看板**、离线跑批平台
- SSE token 累加、Agent 逐步 tool SSE

拷贝进业务项目时按需自行实现；本仓保持 cookbook 粒度。

详见 [backlog.md](backlog.md)。
