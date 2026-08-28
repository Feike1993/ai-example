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

- [phase4.md](docs/phase4.md)、[phase5.md](docs/phase5.md)
- [09-hybrid-rag.md](docs/samples/09-hybrid-rag.md)、[10-eval.md](docs/samples/10-eval.md)
