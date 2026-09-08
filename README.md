# AI Agent 学习样例

独立的 Agent 学习 cookbook。**基础闭环 v0.2.0**（一至三期 + 基础补丁）；进阶至**第十三期**：Playground 进阶侧栏按主题分组（第十二期记忆×检索闭环等能力见各 phase 文档）。

**宣传**：[基础闭环宣传页](frontend/promo.html)（含进阶五组逻辑图墙 `#advanced-groups`） · [方版宣传图](frontend/public/promo/opensource-poster-1080.png)（源文件 [poster.html](frontend/poster.html)，导出 `cd frontend && pnpm poster:export`） · [仓库结尾卡](frontend/public/promo/repo-endcard-1080x1920.png)（竖版，源文件 [endcard.html](frontend/endcard.html)，导出 `cd frontend && pnpm endcard:export`）

- **第一期**：Chat → 结构化输出 → Tool Calling → ReAct Agent Loop
- **第二期**：MCP → RAG（pgvector）
- **第三期**：上下文工程 → 多 Agent
- **基础补丁**：Prompt 外置 / Token 用量 / RAG 空检索拒答 / Agent 答案流式（见 [baseline-patches](docs/baseline-patches.md)）
- **第四期**：Hybrid RAG → golden 评测（见 [phase4](docs/phase4.md)）
- **第五期**：持久会话（PostgreSQL）→ 长期记忆（pgvector）（见 [phase5](docs/phase5.md)）
- **第六期**：MCP 远端拆分 → 完整 HyDE（见 [phase6](docs/phase6.md)）
- **第七期**：语义分块 → 父子文档（见 [phase7](docs/phase7.md)）
- **第八期**：自动抽记忆 → 召回策略对照（见 [phase8](docs/phase8.md)）
- **第九期**：MCP Bearer 鉴权（见 [phase9](docs/phase9.md)）
- **第十期**：Agent 逐步 tool SSE → TokenUsage 累加（见 [phase10](docs/phase10.md)）
- **第十一期**：输出护栏 → RAG 强制 citation（见 [phase11](docs/phase11.md)）
- **第十二期**：记忆辅助改写 → RAG vs 记忆对照（见 [phase12](docs/phase12.md)）
- **第十三期**：进阶侧栏主题分组（见 [phase13](docs/phase13.md)）

Java：**Spring Boot 4.1 + Spring AI 2.0 + Gradle**；Python：**LangGraph / MCP SDK**；前端：**Vite + React playground**（另有工业级独立入口）。

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
| 6 | MCP 远端 | 独立 Server + Client | `POST /ai-example/mcp/chat`（需 8081） | `samples.mcp_client_http` |
| 6 | HyDE | 假想文档 Embedding | `POST /ai-example/rag/query/compare-expansion` | `samples.hyde_rag` |
| 7a | 语义分块 | token vs 结构切 | `POST /ai-example/rag/query/compare-chunking` | `samples.semantic_chunk` |
| 7b | 父子文档 | 子检索父上下文 | `chunkingStrategy=parent_child` | `samples.parent_child_rag` |
| 8a | 自动抽记忆 | 对话抽事实 → remember | `POST /ai-example/memory/extract` | `samples.memory_extract` |
| 8b | 召回对照 | topK / 阈值 / 有无记忆 | `POST /ai-example/memory/recall/compare` | `samples.memory_recall_compare` |
| 9 | MCP Bearer | Server 校验 + Client 带凭证 | `MCP_BEARER_TOKEN` + `/mcp` | `samples.mcp_client_http` |
| 10 | Agent 逐步 SSE | tool_call / tool_result 实时推送 | `GET /ai-example/agent/react/stream` | `samples.react_agent` |
| 10 | Usage 累加 | 多轮 TokenUsage 合计 | `POST /ai-example/agent/react`（`usage`） | `samples.react_agent` |
| 11 | 输出护栏 | 词表 + 结构校验 | `POST /ai-example/guardrail/chat` | `samples.guardrail_chat` |
| 11 | RAG Citation | 强制可校验引用 | `POST /ai-example/rag/query`（`citationMode=required`） | `samples.rag_citation` |
| 12 | 记忆辅助改写 | memory_rewrite | `POST /ai-example/rag/query`（`queryExpansion=memory_rewrite`） | `samples.memory_informed_rag` |
| 12 | RAG vs 记忆 | 双路对照 | `POST /ai-example/rag/query/compare-memory` | `samples.rag_memory_compare` |

文档：[学习路径](docs/learning-path.md) · [基础补丁](docs/baseline-patches.md) · [集成说明](docs/integration.md) · [第二期](docs/phase2.md) · [第三期](docs/phase3.md) · [第四期](docs/phase4.md) · [第五期](docs/phase5.md) · [第六期](docs/phase6.md) · [第七期](docs/phase7.md) · [第八期](docs/phase8.md) · [第九期](docs/phase9.md) · [第十期](docs/phase10.md) · [第十一期](docs/phase11.md) · [第十二期](docs/phase12.md) · [第十三期](docs/phase13.md) · [CHANGELOG](CHANGELOG.md) · [刻意不做 backlog](docs/backlog.md)

## 环境

- JDK **25**
- Python **3.11+**（[uv](https://docs.astral.sh/uv/)）
- Node.js **22.13+**（`pnpm@11` 依赖 `node:sqlite`；在 **frontend/** 目录执行，不要在仓库根目录跑 `pnpm start`）
- Docker（完整容器化运行，或仅启动 PostgreSQL + pgvector；工业级链路另需 Redis）
- API Key：**聊天**默认 DeepSeek；**Embedding（RAG）**需要 DashScope

```bash
cp .env.example .env
# PROVIDER_DEEPSEEK_API_KEY=...   # 或 AI_API_KEY
# PROVIDER_DASHSCOPE_API_KEY=...  # RAG Embedding 必填
```

## 使用 Docker Compose 运行完整系统

完整模式会启动 PostgreSQL/pgvector、**Redis**、独立 MCP Server、Java 主服务和 Nginx 前端。浏览器只需访问 Nginx，API 与 SSE 通过同源 `/ai-example` 路径代理到 Java。

Redis 给工业级链路用：run 状态与 SSE 断线续传（`Last-Event-ID`）。教学样例（`/rag`、`/chat`、`/agent` 等）不读 Redis，但 readiness 探针已纳入 `redis`，没起 Redis 时 `/actuator/health/readiness` 会不健康。

```bash
cp .env.example .env
# 编辑 .env，至少填入实际使用的 Provider Key

#1. `docker compose up`
#根据 `docker-compose.yml` 启动 / 创建容器
#2. `--build`
#**先重新构建镜像**，再启动容器（修改了 Dockerfile 时必须加这个，否则用旧镜像）
#3. `-d` = `--detach`
#**后台守护进程运行**，终端不会卡住、不打印实时日志
#4. `--wait`
#等待所有服务**启动健康检查通过**之后，命令才结束退出；
#如果服务启动失败 / 健康检查不通过，这条命令最终返回失败退出码（适合 CI 自动化脚本）

docker compose up -d --build --wait
docker compose ps
```

打开 http://localhost:8088 。默认仅发布前端端口；Java、MCP、PostgreSQL、Redis 只在 Compose 内网可见。教学场入口 `/`，工业级入口 `/industrial.html`。

调试时如需从宿主机直连 `5432`、`6379`、`8080`、`8081`：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait
```

健康检查与日志：

```bash
curl -fsS http://localhost:8088/healthz
curl -fsS http://localhost:8088/ai-example/actuator/health/readiness
curl -fsS http://localhost:8088/ai-example/
docker compose logs -f java mcp-server frontend
```

验证 SSE 时使用 `curl -N`，避免客户端缓冲掩盖代理问题：

```bash
curl -N "http://localhost:8088/ai-example/chat/stream?prompt=%E4%BD%A0%E5%A5%BD"
```

停止服务：

```bash
docker compose down
```

普通 `down` 会保留数据库命名卷。**不要随意执行 `docker compose down -v`，该命令会永久删除本地数据库数据。**

> `.env` 仅由 Compose 或 Gradle `bootRun` 注入；打包后的 `java -jar` 不会自动读取仓库 `.env`。`ai/ai` 与 `dev-mcp-token` 仅适合本地演示，非本地部署必须替换。MCP Client 与 Server 的 `MCP_BEARER_TOKEN` 必须一致。

## 跑 Java

### 数据库迁移（Flyway）

建表由 Flyway 在启动期完成，脚本在 [java/src/main/resources/db/migration](java/src/main/resources/db/migration)：

| 版本 | 内容 |
| --- | --- |
| `V1` | 接管教学样例的 `chat_session_message`（原先由 `JdbcChatSessionStore` 的 `@PostConstruct` 建） |
| `V2` | 工业级会话表 `prod_chat_session` / `prod_chat_message` |

两件事值得注意：

**PostgreSQL 成了启动硬依赖。** 这与 `spring.datasource.hikari.initialization-fail-timeout: -1` 的取舍相反——那个设置是为了「没有 Docker 也能把进程起起来」。表结构对不上时让进程起不来，好过跑起来之后每个请求各报各的错。想保留原来的调试体验就设 `FLYWAY_ENABLED=false`，此时会话表不存在，`PRODUCTION_SESSION_ENABLED` 要一并关掉。

**Flyway 不管向量库。** `vector_store` 仍归 Spring AI 的 `initialize-schema`，全文索引仍归 `RagKeywordRetriever`。同一个对象只能有一个 owner，否则迁移与运行期 DDL 会互相打架。

存量库（已有 `chat_session_message`、没有 `flyway_schema_history`）不需要手工处理：`baseline-on-migrate: true` 会自动接管。

### Redis 要不要起？

| 你要跑的内容 | PostgreSQL | Redis | 说明 |
| --- | --- | --- | --- |
| 仅 Chat / Tools / Agent 等非 RAG 样例 | **需要**（Flyway 迁移）| 可选 | 不想起库就设 `FLYWAY_ENABLED=false` |
| RAG / 记忆 / 持久会话（教学样例） | **需要** | 可选 | 样例路径不读 Redis |
| 工业级 `/api/v1/**`（含 SSE 断线续传） | **需要** | **需要** | 默认 `PRODUCTION_ENABLED=true`、`PRODUCTION_EVENT_LOG=redis`；Redis 挂了接口 503，不拖垮启动 |
| 工业级多轮会话 | **需要** | 建议 | `PRODUCTION_SESSION_ENABLED=true`；`PRODUCTION_SESSION_LOCK=memory` 可不用 Redis，但多实例下等于没有锁 |
| 只想先关工业级、专心跑样例 | 按上表 | 可不起 | `PRODUCTION_ENABLED=false` |

本地推荐直接把 Postgres + Redis 一起起：

```bash
# RAG / 记忆需要向量库；工业级 SSE 续传需要 Redis
# 调试覆盖文件负责把端口发布到宿主机（5432 / 6379）
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis

# 第六期 MCP 远端（默认 mode=remote）：另开终端起 Server
cd mcp-server && ./gradlew bootRun

cd java
./gradlew bootRun
```

> 若控制台出现 `System::loadLibrary` / `Use --enable-native-access=ALL-UNNAMED`：这是 **Java 25 对 Netty 原生库** 的提示，不是业务错误。`./gradlew bootRun` 已带该参数。若用 **IDE 直接跑主类**，请在 VM options 加上 `--enable-native-access=ALL-UNNAMED`，或在 `.env` 里设 `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`。

切回二期同进程 MCP：面板切 `inprocess`，或 `MCP_MODE=inprocess`（初始值；亦可用 `PUT /mcp/mode`）。旧法：`MCP_SERVER_ENABLED=true MCP_CLIENT_ENABLED=false`。

```bash
curl http://localhost:8080/ai-example/
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","prompt":"我叫小明","strategy":"trim"}'
curl -s http://localhost:8080/ai-example/multiagent/run \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"查一下北京天气，再写一句出行建议"}'

# 工业级：同步问答 / 流式（需 Redis；流式只有收到 done 才算成功）
curl -s http://localhost:8080/ai-example/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"这个项目的 RAG 是怎么做的？"}'
curl -N "http://localhost:8080/ai-example/api/v1/chat/stream?question=RAG"

# 多轮：sessionId 不传则由后端新建，并在首条 meta 事件里回传，下一轮带上它即可
curl -N "http://localhost:8080/ai-example/api/v1/chat/stream?question=RAG&sessionId=demo-1"
curl -s http://localhost:8080/ai-example/api/v1/sessions/demo-1
curl -s -X DELETE http://localhost:8080/ai-example/api/v1/sessions/demo-1
```

测试：

```bash
cd java && ./gradlew test
# 可选 pgvector 集成（需 Docker）：
# RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT
# 可选 Redis 事件回放 / 会话锁集成（需 Docker）：
# RUN_REDIS_IT=true ./gradlew test --tests RedisRunEventLogIT --tests RedisSessionLockIT
# 可选会话存储集成：事务、并发取号、幂等（需 Docker）
# RUN_SESSION_IT=true ./gradlew test --tests ProductionChatSessionStoreIT
```

集成测试默认用 Testcontainers 起容器。CI 上常以 service 形式提供依赖，容器套容器反而跑不起来；
本机 Testcontainers 与某些 Docker 版本不兼容时同理。这两种情况直接指向已有实例：

```bash
RUN_SESSION_IT=true SESSION_IT_JDBC_URL=jdbc:postgresql://localhost:5432/ai_example \
  ./gradlew test --tests ProductionChatSessionStoreIT
RUN_REDIS_IT=true REDIS_IT_HOST=localhost REDIS_IT_PORT=6379 \
  ./gradlew test --tests RedisSessionLockIT
```

## 工业级链路（与 samples 分开）

后端包 `com.feike.ai.production`，HTTP 前缀 `/ai-example/api/v1/**`，与教学样例路径不重叠。前端独立入口 [industrial.html](frontend/industrial.html)（样例场侧栏也有跳转）。

- 配置前缀 `app.production.*`（环境变量 `PRODUCTION_*` / `REDIS_*`）
- RAG 拆成 ingest / retrieve / generate，不含教学用的 compare 分支
- SSE 契约：`meta → sources → delta* → usage → done|error`，带 `runId` / `seq`；断线用 `GET /api/v1/runs/{runId}/stream` + `Last-Event-ID` 续传

### 多轮会话：教学版修好了什么

[JdbcChatSessionStore](java/src/main/java/com/feike/ai/samples/context/JdbcChatSessionStore.java) 是教学实现，刻意保留了几处生产不该有的写法；
修好的版本在 [com.feike.ai.production.session](java/src/main/java/com/feike/ai/production/session)，两边可以直接对照读。

| 问题 | 教学版 | 生产版 |
| --- | --- | --- |
| 半个 turn | user 与 assistant 分两次裸写 | 同一事务，要么都在要么都不在 |
| 序号竞态 | `SELECT MAX(seq)` 再 INSERT | `INSERT ... SELECT COALESCE(MAX(seq),-1)+1` 单语句取号，复合主键冲突后重取 |
| 跨实例并发 | JVM `synchronized`，多实例失效 | Redis 会话锁（`SET NX PX` + Lua CAS 释放） |
| 重复提交 | 会写两遍 | `turn_id` 唯一索引 + 写前查重 |
| 建表 | `@PostConstruct` 运行期 DDL | Flyway 版本化迁移 |

三条设计上的取舍：

**只有收到 `done` 才落库。** 取消和报错都不写。宁可丢一轮，也不能让历史里留下一条没有回答的孤儿 user 消息——它会一直参与后续每一轮的 prompt，错误只会不断放大。

**落库失败不改变已发出的结论。** 答案此刻已经流到用户屏幕上了，再发 `error` 只会让人不知道到底成没成。持久化异常降级为 `done` 负载里的 `persisted:false`，前端据此提示「本轮未计入历史」。

**锁不是正确性的前提。** Redis 锁在主从切换、网络分区、持有者停顿超过 TTL 时都可能被两个持有者同时认为归自己所有，所以它只负责减少冲突、给用户一个明确的「会话忙」（`session_busy`，同步接口 409）。真正兜底的是 `prod_chat_message` 的复合主键与 `turn_id` 唯一索引。

## 跑前端

```bash
cd frontend
pnpm install
pnpm dev
```

打开 http://localhost:5173 。侧栏含各期样例（进阶含 Hybrid / 评测 / 记忆 / HyDE；MCP 默认 remote，可面板切 inprocess；remote 需 mcp-server）。工业级入口：http://localhost:5173/industrial.html 。

```bash
pnpm test   # Vitest + Testing Library（SSE / Markdown 等）
pnpm build
```

（请在 `frontend/` 下执行；仓库根目录没有 `package.json`，不要跑 `pnpm start`。）

## 跑 Python 对照

```bash
cd python
uv sync --group dev
uv run python -m ai_example.samples.context_memory
uv run python -m ai_example.samples.multi_agent
uv run python -m ai_example.samples.mcp_client_http
uv run python -m ai_example.samples.hyde_rag
uv run pytest
```
