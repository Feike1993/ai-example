# RAG 检索增强

RAG = 检索 + 生成：

1. **离线**：文档分块 → Embedding → 写入向量库（本仓用 PostgreSQL + pgvector）
2. **在线**：问题向量化 → 相似度检索 topK → 拼进 prompt → Chat 模型回答

注意：

- 分块过大丢精度，过小丢上下文；本演示用固定 token 分块
- Embedding 与 Chat Provider 分离：本仓 Embedding 固定 DashScope `text-embedding-v3`（1024 维）
- GIGO：检索差则生成易胡说；响应里的 `sources` 用来核对「答案从哪来」
