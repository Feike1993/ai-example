# AI Agent 学习样例

独立的 Agent 学习 cookbook。

- **第一期**：Chat → 结构化输出 → Tool Calling → ReAct Agent Loop
- **第二期**：MCP → RAG（pgvector）

Java：**Spring Boot 4.1 + Spring AI 2.0 + Gradle**；Python：**LangGraph / MCP SDK**；前端：**Vite + React playground**。

## 你将学到什么

| 期 | 样例 | 概念 | Java | Python |
| --- | --- | --- | --- | --- |
| 1 | Chat | Token、SSE、TTFT | `POST /ai-example/chat` | `samples.chat` |
| 1 | 结构化输出 | JSON Schema、重试 | `POST /ai-example/structured/ticket` | `samples.structured` |
| 1 | Tool Calling | Function Calling | `POST /ai-example/tools` | `samples.tools` |
| 1 | Agent Loop | ReAct / maxSteps | `POST /ai-example/agent/react` | `samples.react_agent` |
| 2 | MCP | 工具协议标准化 | `POST /ai-example/mcp/chat` | `samples.mcp_client` |
| 2 | RAG | 分块 / Embedding / 检索 | `POST /ai-example/rag/query` | `samples.rag` |

文档：[学习路径](docs/learning-path.md) · [集成说明](docs/integration.md) · [第二期](docs/phase2.md) · [第三期占位](docs/phase3.md)

## 环境

- JDK **25**
- Python **3.11+**（[uv](https://docs.astral.sh/uv/)）
- Node.js **20.19+**（`pnpm`，在 **frontend/** 目录执行，不要在仓库根目录跑 `pnpm start`）
- Docker（仅 RAG：PostgreSQL + pgvector）
- API Key：**聊天**默认 DeepSeek；**Embedding（RAG）**需要 DashScope

```bash
cp .env.example .env
# PROVIDER_DEEPSEEK_API_KEY=...   # 或 AI_API_KEY
# PROVIDER_DASHSCOPE_API_KEY=...  # RAG Embedding 必填
```

## 跑 Java

```bash
# RAG 需要向量库
docker compose up -d

cd java
./gradlew bootRun
```

```bash
curl http://localhost:8080/ai-example/
curl -s http://localhost:8080/ai-example/mcp/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5"}'
curl -s -X POST http://localhost:8080/ai-example/rag/ingest
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"本项目第一期学了什么？"}'
```

测试：

```bash
cd java && ./gradlew test
# 可选 pgvector 集成（需 Docker）：
# RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT
```

## 跑前端

```bash
cd frontend
pnpm install
pnpm dev
```

打开 http://localhost:5173 。侧栏含第一期四个样例 + **MCP**、**RAG**。
（请在 `frontend/` 下执行；仓库根目录没有 `package.json`，不要跑 `pnpm start`。）

## 跑 Python 对照

```bash
cd python
uv sync --group dev
uv run python -m ai_example.samples.mcp_client
uv run python -m ai_example.samples.rag
uv run pytest
```
