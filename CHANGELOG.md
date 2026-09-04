# Changelog

本仓库遵循 [Conventional Commits](https://www.conventionalcommits.org/)；版本号为里程碑 tag，不表示业务产品发版节奏。

## [v0.2.0] — 2026-08-27

**基础闭环 baseline**：一至三期样例（01–08）+ 基础补丁 A1–A5。

### 一至三期（已纳入）

- 第一期：Chat / 结构化输出 / Tool Calling / ReAct Agent Loop
- 第二期：MCP / RAG（pgvector）
- 第三期：上下文工程（trim / summarize）/ 同进程多 Agent

### 基础补丁

- **A1** Prompt 模板化：`PromptLoader` + `prompts/chat-assistant.st` / `agent-react.st`
- **A2** 同步接口 `TokenUsage`：Chat / Tools / Structured 等响应带回用量
- **A3** RAG 空检索拒答：`retrievalEmpty` + 可配置短路
- **A4** Agent ReAct 最小流式：`GET /agent/react/stream`（仅最终答案 SSE）
- **A5** 文档与自测：[baseline-patches.md](docs/baseline-patches.md)、学习路径分层

### 文档

- [learning-path.md](docs/learning-path.md)：基础阶段完成标准 / 进阶入口
- [backlog.md](docs/backlog.md)：标注 v0.2.0 覆盖范围

### 不包含（进阶 / backlog）

Hybrid RAG、golden 评测、Redis 持久会话、逐步 tool SSE、流式 token 累加等。第四期见 [phase4.md](docs/phase4.md)。

## [Unreleased] — 第四期进阶

### Hybrid RAG

- PostgreSQL 全文 + pgvector 向量 + **RRF** 融合
- `POST /rag/query` 支持 `retrievalMode: vector|hybrid`
- `POST /rag/query/compare` 并排对照
- sources 增加 `vectorRank` / `keywordRank` / `rrfScore`

### Agent 评测

- `classpath:eval/golden/*.json` + `POST /eval/run`
- 前端 **评测** Tab；Python `eval_runner` / `hybrid_rag` 对照

### 文档

- [phase4.md](docs/phase4.md)
- [09-hybrid-rag.md](docs/samples/09-hybrid-rag.md)、[10-eval.md](docs/samples/10-eval.md)

## [Unreleased] — 第五期进阶

### 持久会话

- `ChatSessionStore`：默认 `JdbcChatSessionStore`（PostgreSQL），可切 `memory`
- 响应字段 `store`；重启后同一 `sessionId` 可续聊

### 长期记忆

- `corpus=long-term-memory` + `/memory/remember|recall|chat`
- 前端 **长期记忆** Tab；Python `long_term_memory` 对照

### 文档

- [phase5.md](docs/phase5.md)
- [11-persist-session.md](docs/samples/11-persist-session.md)、[12-long-term-memory.md](docs/samples/12-long-term-memory.md)

## [Unreleased] — 第六期进阶

### MCP 远端拆分

- 独立 [`mcp-server/`](mcp-server/)（8081，STREAMABLE `/mcp`）
- 主应用默认 `app.ai.mcp.mode=remote`；`inprocess` 保留二期路径
- 响应字段 `mode`；前端 MCP Tab 提示先起旁进程

### 完整 HyDE

- `queryExpansion: none|rewrite|hyde`（`rewriteQuery=true` 兼容）
- 假想文档 Embedding 检索；可选与原问题向量路 RRF
- `POST /rag/query/compare-expansion`；sources 不含假想正文
- 前端 **HyDE** Tab；Python `hyde_rag` / `mcp_client_http`

### 文档

- [phase6.md](docs/phase6.md)
- [13-mcp-remote.md](docs/samples/13-mcp-remote.md)、[14-hyde.md](docs/samples/14-hyde.md)

## [Unreleased] — 第七期进阶（7a 语义分块 + 7b 父子文档）

### 语义分块（7a）

- `SemanticMarkdownSplitter`：标题 / 空行切 + 软合并（无 LLM）
- ingest `strategy=token|semantic|all`；corpus `ai-example-demo-semantic`
- `POST /rag/query/compare-chunking` 对照 sources
- 前端 **SemanticChunk** Tab；Python `semantic_chunk`

### 父子文档（7b）

- ingest `parent_child`：子块 Embedding，metadata 含 `parentText`
- 检索后 `expand-parent` 去重父块拼上下文
- compare-chunking 第三路 `parentChild`
- 前端 **ParentChild** Tab；Python `parent_child_rag`

### 文档

- [phase7.md](docs/phase7.md)、[15-semantic-chunk.md](docs/samples/15-semantic-chunk.md)、[16-parent-child.md](docs/samples/16-parent-child.md)

## [Unreleased] — 第八期进阶（8a 自动抽记忆 + 8b 召回策略对照）

### 自动抽记忆（8a）

- `POST /memory/extract`：messages / turns / sessionId 三选一 → Chat 抽事实 JSON → 现有 `remember`
- 配置 `app.ai.memory.extract-max-facts`（默认 5）
- 前端 **MemoryExtract** Tab；Python `memory_extract`

### 召回策略对照（8b）

- `recall` 支持可选 `similarityThreshold`（有 score 时过滤）
- `POST /memory/recall/compare`：lowTopK / highTopK / withThreshold 三套 sources
- `POST /memory/chat/compare`：withMemory / withoutMemory 两套答案
- 前端 **MemoryCompare** Tab；Python `memory_recall_compare`

### 文档

- [phase8.md](docs/phase8.md)、[17-memory-extract.md](docs/samples/17-memory-extract.md)、[18-memory-recall-compare.md](docs/samples/18-memory-recall-compare.md)

## [Unreleased] — 第九期进阶（MCP Bearer 鉴权）

### MCP Bearer

- `mcp-server`：`/mcp` 校验 `Authorization: Bearer`（`MCP_BEARER_TOKEN`，默认 `dev-mcp-token`）
- 主应用 Client：`McpClientAuthConfiguration` 注入同密钥；inprocess 不要求 Bearer
- 401 时 503 文案提示核对两端 token；前端 MCP Tab / Python `mcp_client_http` 同步说明

### 文档

- [phase9.md](docs/phase9.md)、[19-mcp-bearer.md](docs/samples/19-mcp-bearer.md)

## [Unreleased] — 第十期进阶（可观测流式）

### Agent 逐步 tool SSE

- `ReactAgentLoop` 同步 / 流式共用完整多跳；`Progress` 推送 `tool_call` / `tool_result`
- `GET /agent/react/stream`：逐步事件 + 终答 + `usage` + `done`（可选聚合 `steps`）
- 前端进阶 **AgentToolSse**；Python `run_observable_demo`

### TokenUsage 累加

- 同步 `Trace.usage` / `usageCalls`；`TokenUsageExtractor.sum`
- SSE `event:usage` 给最终合计；前端 **AgentUsage**

### 文档

- [phase10.md](docs/phase10.md)、[20-agent-tool-sse.md](docs/samples/20-agent-tool-sse.md)、[21-stream-token-usage.md](docs/samples/21-stream-token-usage.md)

## [Unreleased] — 第十一期进阶（护栏与引用）

### 输出护栏

- `POST /guardrail/chat`：input/output deny-words + 可选 SafeEnvelope；响应 `checks[]`
- 配置 `app.ai.guardrail.deny-words`；前端 **Guardrail**；Python `guardrail_chat`

### RAG 强制 citation

- `citationMode=none|required`；`CitationValidator` 校验 sourceId∈sources
- 失败 `citationValid=false` + 固定拒答；前端 **RagCitation**；Python `rag_citation`

### 文档

- [phase11.md](docs/phase11.md)、[22-output-guardrail.md](docs/samples/22-output-guardrail.md)、[23-rag-citation.md](docs/samples/23-rag-citation.md)

## [Unreleased] — 第十二期进阶（记忆×检索闭环）

### 记忆辅助改写

- `queryExpansion=memory_rewrite`：recall → 改写 → RAG；响应 `memoryHints` / `rewrittenQuery`
- `POST /rag/query/compare-memory-rewrite`：none / rewrite / memory_rewrite 三路
- 前端 **MemoryInformedRag**；Python `memory_informed_rag`

### RAG vs 记忆对照

- `POST /rag/query/compare-memory`：同问双路；`generateAnswers` 可只比 sources
- 前端 **RagMemoryCompare**；Python `rag_memory_compare`

### 文档

- [phase12.md](docs/phase12.md)、[24-memory-informed-rewrite.md](docs/samples/24-memory-informed-rewrite.md)、[25-rag-vs-memory-compare.md](docs/samples/25-rag-vs-memory-compare.md)
