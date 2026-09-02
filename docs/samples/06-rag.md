# 06 RAG

## 概念

RAG = 检索 + 生成：

1. **离线**：文档 → 分块 → Embedding → 向量库
2. **在线**：问题向量化 → 相似度检索 → 拼上下文 → LLM 回答

注意：

- 分块过大丢精度，过小丢语义；本仓用固定 token 分块做演示
- Chat 模型不一定有 Embedding：本仓 **Embedding 固定 DashScope `text-embedding-v3`**，Chat 仍可切换 DeepSeek 等
- GIGO：检索差，生成再强也容易胡说；前端展示 `sources` 便于核对
- **空检索拒答**：命中条数 `< app.ai.rag.min-sources`（默认 1）时 `retrievalEmpty=true`；若 `skip-llm-when-empty=true`（默认）则跳过 LLM，返回固定拒答，避免编造

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

空检索（未 ingest、或索引无命中时）：

```bash
# 期望 retrievalEmpty=true；默认 skip-llm-when-empty 时 answer 为固定拒答
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"完全不存在的虚构关键词 xyz123"}' | jq '.retrievalEmpty, .answer, .sources'
```

配置（`application.yml`）：

```yaml
app.ai.rag:
  min-sources: 1              # 命中 < 此值视为空检索
  skip-llm-when-empty: true   # true=短路固定拒答；false=仍调 LLM，但 system 约束「无则说不知道」
```

Python 对照（内存向量，不依赖 Docker）：

```bash
cd python
uv run python -m ai_example.samples.rag
```

## 对照 / 拷贝

- Java：`samples.rag` + `docker-compose.yml` 的 pgvector
- Embedding：`LlmProviderRegistry.embeddingModel()`（DashScope）；Chat 仍可切换
- Python：`samples.rag` 内存向量 + 余弦相似度；空 hits 时同样可短路拒答
- Embedding Provider 与 Chat Provider **分开配置**
- 详见 [backlog](../backlog.md)、[baseline-patches](../baseline-patches.md) A3
- **强制 citation（第十一期）**：`citationMode=required` 时结构化引用并校验 `sourceId`，见 [23-rag-citation.md](23-rag-citation.md)

## 测试

```bash
# 分块 + 空检索拒答单测（无需 Docker / Key）
cd java && ./gradlew test --tests RagTokenSplitterTest --tests RagEmptyRetrievalTest

# 可选：Testcontainers pgvector（需本机 Docker）
RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT
```
