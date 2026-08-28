# 刻意不做 / 后续候选（Backlog）

本仓是 Agent **学习 cookbook**，不是业务系统。下列条目集中登记「当时为什么不做」和「若要做可挂哪一期」，避免散落在各期 plan 与样例文末。

字段含义：

- **现状**：`已覆盖` / `候选` / `明确不做（本仓）`
- **若要做**：建议挂到第几期或「业务项目自行实现」

## v0.2.0 Baseline 覆盖范围

**发版节点**：一至三期样例（01–08）+ [基础补丁 A1–A5](baseline-patches.md) 全部合并并通过自测后，打 tag `v0.2.0`。

| 范围 | 包含 | 不包含（见下文候选 / 第四期） |
| --- | --- | --- |
| 样例 | Chat、结构化、Tools、ReAct、MCP、RAG、上下文、多 Agent | Hybrid RAG、golden 评测 |
| 横切补丁 | Prompt 外置（A1）、同步 `TokenUsage`（A2）、RAG 空检索拒答（A3）、Agent 答案流式（A4） | SSE token 累加、逐步 tool SSE |
| 基础设施 | 进程内会话、同进程 MCP、pgvector 演示 | Redis、持久会话、MCP 第二进程 |
| 产品面 | Vite playground | 知识库后台、评测看板、鉴权限流 |

第四期在 `v0.2.0` 之后于分支 `cursor/phase4-hybrid-eval` 开发；进阶入口见 [learning-path.md](learning-path.md)。

## 已在后续期覆盖（履历）

| 主题 | 首次提出 | 现状 | 覆盖于 | 备注 |
| --- | --- | --- | --- | --- |
| 前端 playground | 第一期故意不引入 | 已覆盖 | 一期后补齐；二期加 MCP/RAG Tab | 约定在 `frontend/` 下 `pnpm dev` |
| PostgreSQL + pgvector | 第一期不做 | 已覆盖 | 第二期 RAG | `docker compose up -d` |
| MCP Server / Client | 第一期不做 | 已覆盖（学习形态） | 第二期 | 同进程暴露 `/mcp`；生产拆分仍为候选 |
| 跨会话记忆 / 上下文压缩 | 一期 Agent 仅当轮消息；二期占位 | 已覆盖（进程内） | 第三期 | trim / summarize；非 Redis 持久化 |
| 多 Agent | 二期刻意不做 | 已覆盖（同进程） | 第三期 | Orchestrator–Subagent + 响应内轨迹 |
| HTTP 响应返回 token 用量（同步接口） | 维护期候选 | 已覆盖 | v0.2.0 补丁 A2 | `TokenUsage` DTO；Chat / Tools / Structured 至少三项 |
| Chat / Agent system prompt 外置 | integration 约定 | 已覆盖 | v0.2.0 补丁 A1 | `PromptLoader` + `chat-assistant.st` / `agent-react.st` |
| RAG 空检索拒答 | 第二期 RAG 入门 | 已覆盖 | v0.2.0 补丁 A3 | `retrievalEmpty` + 短路；混合检索仍属第四期 |
| Agent ReAct 最终答案流式 | 第二期后横切 | 已覆盖（最小） | v0.2.0 补丁 A4 | 仅 answer SSE；逐步 tool SSE 仍为候选 |
| 混合检索（向量 + 关键词 + RRF） | 第二期 | 已覆盖 | 第四期 Hybrid RAG | `retrievalMode=hybrid` + compare API |
| 完整评测平台（golden / 回归） | 第三期 | 已覆盖（最小 harness） | 第四期 Eval | `POST /eval/run`；无看板 |

## 仍刻意不做（候选）

### 基础设施 / 产品形态

| 主题 | 首次提出 | 现状 | 为何当时不做 | 若要做 |
| --- | --- | --- | --- | --- |
| Redis（会话外存 / Stream / 缓存） | 第二期 | 候选 | 控制基础设施面；cookbook 用内存即可讲清概念 | 第四期或业务项目 |
| 语音 / ASR–TTS | 第二期 | 候选 | 与 Agent 主线正交 | 业务项目 |
| MCP 独立第二进程 Server | 第二期 | 候选 | 同 Boot 即可学协议，避免双进程样板 | 拷贝进业务时拆分 |
| MCP Client 真连远端 Streamable HTTP | 第二期 | 候选 | 避免启动期 Client 连自己的鸡生蛋 | 业务侧启用 `spring.ai.mcp.client` |
| RAG 知识库产品化（上传后台 / 权限 / 多租户） | 第二期 | 明确不做（本仓） | 样例不是知识库产品 | 业务项目 |
| Provider 配置 DB 热更新 | 第一期 | 候选 | `.env` + `app.ai.providers` 足够学习 | 业务项目 |
| 绑定 interview-guide 特殊环境变量 / 文案 | 维护期 | 明确不做（本仓） | 本仓独立 cookbook | — |

### RAG 进阶

| 主题 | 首次提出 | 现状 | 为何当时不做 | 若要做 |
| --- | --- | --- | --- | --- |
| 混合检索（向量 + BM25 + RRF） | 第二期 | 已覆盖 | 第四期 | 见 [09-hybrid-rag.md](samples/09-hybrid-rag.md) |
| 查询改写 / HyDE | 第二期 | 候选 | 第四期（最小 rewriteQuery） / 第五期 | 完整 HyDE 留 phase5 |
| 异步索引管道（如 Redis Stream） | 第二期 | 候选 | 依赖 Redis | 业务项目 |
| 语义分块 / 父子文档 | 第二期 | 候选 | 固定 token 分块足够演示权衡 | 第四期 |

### 上下文 / 记忆进阶

| 主题 | 首次提出 | 现状 | 为何当时不做 | 若要做 |
| --- | --- | --- | --- | --- |
| Redis / DB 持久会话 | 第三期 | 候选 | 进程内 Map 即可对比 trim vs summarize | 第四期 |
| 长期记忆写入向量库 | 第三期 | 候选 | 与 RAG 进阶合并更合适 | 第四期 |
| 精确 tokenizer 计费 | 第三期 | 候选 | 字符/4 启发式足够建立预算直觉 | 业务项目 |
| 完整 Harness 六层产品化 | 第三期占位 | 明确不做（本仓） | 文档点名即可；代码只落记忆 + 预算约束 | 业务架构 |

### 多 Agent / 可观测进阶

| 主题 | 首次提出 | 现状 | 为何当时不做 | 若要做 |
| --- | --- | --- | --- | --- |
| 分布式 / 多进程 Agent、消息总线 | 第三期 | 候选 | 同进程即可讲清交接与信息边界 | 业务项目 |
| 完整评测平台（离线集 / 回归 / 看板） | 第三期 | 已覆盖（harness） | 第四期 Eval | 看板仍不做 |
| Java 侧独立工作流 / 图引擎 | 第三期 | 明确不做（本仓） | 用结构化交接即可；Python 仅最小 LangGraph | 业务项目 |

### 横切

| 主题 | 首次提出 | 现状 | 为何当时不做 | 若要做 |
| --- | --- | --- | --- | --- |
| SSE / 流式 token 累加、Agent 逐步 usage | v0.2.0 补丁 A2 后 | 候选 | 同步 `TokenUsage` 已够建立计费直觉；流式收尾与多轮累加需额外约定 | 业务项目或进阶 backlog |
| Agent 逐步 tool_call 实时 SSE | v0.2.0 补丁 A4 后 | 候选 | 仅最终答案流式即可演示 TTFT；逐步 SSE 复杂度高 | 业务项目 |
| 鉴权、限流、审计落库 | 全程 | 明确不做（本仓） | 学习样例无安全产品面 | 业务项目 |
| 有副作用的真实外部工具 | 第一期 | 明确不做（本仓） | 演示工具保持幂等、可离线 | 业务项目 |
| 仓库根目录 `package.json` / `pnpm start` | 前端引入后 | 明确不做（本仓） | 避免与 Vite 工程混淆 | — |

各期样例文末可写一句：详见 [backlog](backlog.md)。
