# 06 RAG

## 概念

RAG = 检索 + 生成：

1. **离线**：文档 → 分块 → Embedding → 向量库
2. **在线**：问题向量化 → 相似度检索 → 拼上下文 → LLM 回答

注意：

- 分块过大丢精度，过小丢语义；本仓用固定 token 分块做演示
- Chat 模型不一定有 Embedding：本仓 **Embedding 固定 DashScope `text-embedding-v3`**，Chat 仍可切换 DeepSeek 等
- GIGO：检索差，生成再强也容易胡说；前端展示 `sources` 便于核对

## 怎么跑

```bash
docker compose up -d
# .env 里填 PROVIDER_DASHSCOPE_API_KEY（Embedding）以及聊天用的 Key
cd java && ./gradlew bootRun
```

```bash
curl -s -X POST http://localhost:8080/ai-example/rag/ingest
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"本项目第一期学了什么？","provider":"deepseek"}'
curl -N 'http://localhost:8080/ai-example/rag/query/stream?question=RAG是什么&provider=deepseek'
```

Python 对照（内存向量，不依赖 Docker）：

```bash
cd python
uv run python -m ai_example.samples.rag
```

## 对照 / 拷贝

- Java：`samples.rag` + `docker-compose.yml` 的 pgvector
- Embedding：`LlmProviderRegistry.embeddingModel()`（DashScope）；Chat 仍可切换
- Python：`samples.rag` 内存向量 + 余弦相似度
- 生产可加混合检索（向量 + BM25 + RRF）、查询改写；本仓刻意不做
- Embedding Provider 与 Chat Provider **分开配置**，不要假设同一网关两者都有

## 测试

```bash
# 分块单元测（无需 Docker / Key）
cd java && ./gradlew test --tests RagTokenSplitterTest

# 可选：Testcontainers pgvector（需本机 Docker）
RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT
```
