# 基础补丁清单（v0.2.0 发版前）

一至三期已跑通 Agent **基础闭环**（Chat / 结构化 / Tools / ReAct / MCP / 基础 RAG / 上下文 / 同进程多 Agent）。在进入 Hybrid RAG、golden 评测等**进阶**内容前，用本节补丁补齐横切缺口，再发版 `v0.2.0`。

每项保持 cookbook 风格：**小 diff、可拷贝、有文档一句**；不新开 Redis、不做知识库产品。

学习路径分层见 [learning-path.md](learning-path.md)；刻意不做项见 [backlog.md](backlog.md)。

## 补丁与样例对应

| 补丁 | 主题 | 对应样例 | 主要改动 |
| --- | --- | --- | --- |
| A1 | Prompt 模板化 | [01-chat](samples/01-chat.md)、[04-agent](samples/04-agent.md)、[08-multi-agent](samples/08-multi-agent.md) | `prompts/*.st` + `PromptLoader` |
| A2 | 同步接口 Token 用量 | [01-chat](samples/01-chat.md)、[02-structured](samples/02-structured.md)、[03-tools](samples/03-tools.md) | 公共 `TokenUsage` DTO |
| A3 | RAG 空检索拒答 | [06-rag](samples/06-rag.md) | `retrievalEmpty` + 短路策略 |
| A4 | Agent ReAct 流式（最小） | [04-agent](samples/04-agent.md) | `GET /agent/react/stream` |
| A5 | 文档与自测 | 全量 | 本文 + README 勾选 |

---

## A1. Prompt 模板化

**现状**：仅 `prompts/extract-ticket.st` 用于 structured；integration 要求 prompt 外置，但 Chat / Agent 仍硬编码 system。

**补丁**：

- 新增 `prompts/chat-assistant.st`、`prompts/agent-react.st`（或等价 `.md`）
- 小工具类 `PromptLoader`（classpath 读取 + 简单占位符），Chat / Agent / MultiAgent 复用
- [integration.md](integration.md) 补拷贝说明

**不学新范式**：不做 Prompt 管理平台。

**验收**：

```bash
# 改 prompts/chat-assistant.st 后重启，Chat 回复风格应随之变化
curl -s http://localhost:8080/ai-example/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"你好"}'
```

---

## A2. 同步接口 Token 用量

**现状**：响应只有 `content`；流式与多轮 Agent 累加留进阶。

**补丁**：

- 公共 DTO `TokenUsage`（`prompt` / `completion` / `total`，可为 null）
- 扩展同步响应：`ChatResponse`、`ToolChatResponse`、structured ticket 等 **至少 Chat + Tools + Structured**
- 从 Spring AI `ChatResponse` metadata / usage 提取（提取失败则字段为 null，不阻断）
- 前端 `ChatPanel` 展示用量；其他 Panel 可选

**刻意不做**：SSE 流式累加 token、Agent 逐步 usage（见 [backlog](backlog.md)）。

**验收**：

```bash
curl -s http://localhost:8080/ai-example/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用一句话介绍 Token"}' | jq '.usage'

curl -s http://localhost:8080/ai-example/tools \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样"}' | jq '.usage'

curl -s http://localhost:8080/ai-example/structured/ticket \
  -H 'Content-Type: application/json' \
  -d '{"text":"明天下午开会"}' | jq '.usage'
```

期望：存在 `usage` 字段；网关未返回用量时各字段为 `null`，请求仍成功。

---

## A3. RAG 空检索 / 低召回拒答

**现状**：`RagSampleService` 无结果时上下文为「（无检索结果）」，模型仍可能编造。

**补丁**：

- 配置 `app.ai.rag.min-sources`；hits 为空时短路：固定 system「仅根据检索上下文；无则明确说不知道」+ 可选 `skipLlmWhenEmpty`
- 响应增加 `retrievalEmpty: boolean`
- 更新 [samples/06-rag.md](samples/06-rag.md)；单测 mock 空 hits

**不学新范式**：不做混合检索（属第四期）。

**验收**（需 Docker pgvector 已启动并完成 ingest）：

```bash
# 正常检索
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"什么是 RAG"}' | jq '.retrievalEmpty, .sources'

# 故意低召回：应 retrievalEmpty=true，且不编造语料外内容
curl -s http://localhost:8080/ai-example/rag/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"完全不存在的虚构关键词 xyz123"}' | jq '.retrievalEmpty, .content'
```

---

## A4. Agent ReAct 流式（最小）

**现状**：Chat / RAG 有 SSE；`AgentSampleService.react` 仅同步 Trace。

**补丁**（较简方案）：

- `GET /agent/react/stream` — 仅在 **最终自然语言答案** 阶段 SSE（tool 步骤仍同步完成后一次性返回 steps，或首包 JSON steps + 后续 answer 流）
- `AgentPanel` 增加 SSE 模式开关

**刻意不做**：逐步 tool_call 的实时 SSE（复杂度高，留 [backlog](backlog.md)）。

**验收**：

```bash
# 同步对照
curl -s http://localhost:8080/ai-example/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"查一下北京天气"}' | jq '.steps, .answer'

# 流式：首段含 steps，后续为 answer token
curl -N 'http://localhost:8080/ai-example/agent/react/stream?prompt=查一下北京天气'
```

---

## A5. 发版前自测清单

补丁 A1–A4 合并后执行：

```bash
# Java 单元测试
cd java && ./gradlew test

# Python 对照（可选）
cd python && uv run pytest

# 前端类型检查
cd frontend && pnpm exec tsc --noEmit
```

可选 RAG 集成（需 Docker）：

```bash
cd java && RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT
```

README「基础阶段完成」勾选项与 [CHANGELOG](../CHANGELOG.md) `v0.2.0` 条目一并更新后发版。

---

## 发版后

- Git tag：`v0.2.0`（本地打 tag；不 push 除非明确要求）
- 第四期在分支 `cursor/phase4-hybrid-eval` 开发，见 [learning-path.md](learning-path.md)「进阶入口」
