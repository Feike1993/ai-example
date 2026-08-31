# AI Agent 学习样例

独立的 Agent 学习 cookbook。**基础闭环 v0.2.0**（一至三期 + 基础补丁）；**第四期**：Hybrid RAG + Agent 评测；**第五期**：持久会话 + 长期记忆。

**宣传**：[基础闭环宣传页](frontend/promo.html) · [方版宣传图](frontend/public/promo/opensource-poster-1080.png)（源文件 [poster.html](frontend/poster.html)，导出 `cd frontend && pnpm poster:export`）

- **第一期**：Chat → 结构化输出 → Tool Calling → ReAct Agent Loop
- **第二期**：MCP → RAG（pgvector）
- **第三期**：上下文工程 → 多 Agent
- **基础补丁**：Prompt 外置 / Token 用量 / RAG 空检索拒答 / Agent 答案流式（见 [baseline-patches](docs/baseline-patches.md)）
- **第四期**：Hybrid RAG → golden 评测（见 [phase4](docs/phase4.md)）
- **第五期**：持久会话（PostgreSQL）→ 长期记忆（pgvector）（见 [phase5](docs/phase5.md)）

Java：**Spring Boot 4.1 + Spring AI 2.0 + Gradle**；Python：**LangGraph / MCP SDK**；前端：**Vite + React playground**。

## 基础阶段完成

对照 [学习路径 · 完成标准](docs/learning-path.md#基础阶段完成标准) 与 tag `v0.2.0`：

- [x] 样例 01–08 可跑通（Java + 前端 Tab；Python 对照可选）
- [x] Prompt 外置（`PromptLoader` + `resources/prompts/`）
- [x] 同步 Chat / Tools / Structured 含 `usage`
- [x] RAG 空检索 `retrievalEmpty` 拒答
- [x] `GET /agent/react/stream` 流式终答
- [x] `./gradlew test`、`frontend` `tsc`、可选 `pytest` 通过
- [x] [CHANGELOG](CHANGELOG.md) `v0.2.0` 与 [baseline-patches](docs/baseline-patches.md) 验收说明

## 你将学到什么

| 期 | 样例 | 概念 | Java | Python |
| --- | --- | --- | --- | --- |
| 1 | Chat | Token、SSE、TTFT | `POST /ai-example/chat` | `samples.chat` |
| 1 | 结构化输出 | JSON Schema、重试 | `POST /ai-example/structured/ticket` | `samples.structured` |
| 1 | Tool Calling | Function Calling | `POST /ai-example/tools` | `samples.tools` |
| 1 | Agent Loop | ReAct / maxSteps | `POST /ai-example/agent/react` | `samples.react_agent` |
| 2 | MCP | 工具协议标准化 | `POST /ai-example/mcp/chat` | `samples.mcp_client` |
| 2 | RAG | 分块 / Embedding / 检索 | `POST /ai-example/rag/query` | `samples.rag` |
| 3 | 上下文工程 | trim / summarize | `POST /ai-example/context/chat` | `samples.context_memory` |
| 3 | 多 Agent | Orchestrator–Subagent | `POST /ai-example/multiagent/run` | `samples.multi_agent` |
| 4 | Hybrid RAG | 向量 + 全文 + RRF | `POST /ai-example/rag/query/compare` | `samples.hybrid_rag` |
| 4 | Agent 评测 | golden suite | `POST /ai-example/eval/run` | `samples.eval_runner` |
| 5 | 持久会话 | PG 会话存储 | `POST /ai-example/context/chat` | `samples.context_memory` |
| 5 | 长期记忆 | pgvector 事实库 | `POST /ai-example/memory/chat` | `samples.long_term_memory` |

文档：[学习路径](docs/learning-path.md) · [基础补丁](docs/baseline-patches.md) · [集成说明](docs/integration.md) · [第二期](docs/phase2.md) · [第三期](docs/phase3.md) · [第四期](docs/phase4.md) · [第五期](docs/phase5.md) · [CHANGELOG](CHANGELOG.md) · [刻意不做 backlog](docs/backlog.md)

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
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","prompt":"我叫小明","strategy":"trim"}'
curl -s http://localhost:8080/ai-example/multiagent/run \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"查一下北京天气，再写一句出行建议"}'
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

打开 http://localhost:5173 。侧栏含各期样例（含 **上下文**、**多 Agent**）。
（请在 `frontend/` 下执行；仓库根目录没有 `package.json`，不要跑 `pnpm start`。）

## 跑 Python 对照

```bash
cd python
uv sync --group dev
uv run python -m ai_example.samples.context_memory
uv run python -m ai_example.samples.multi_agent
uv run pytest
```
