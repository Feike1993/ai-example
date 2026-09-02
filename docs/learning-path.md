# 学习路径

## 第一期：最小 Agent 闭环

见历史样例文档 [01](samples/01-chat.md)–[04](samples/04-agent.md)：

1. Chat → 2. 结构化输出 → 3. Tool Calling → 4. ReAct Agent Loop

## 第二期：MCP + RAG

见 [phase2.md](phase2.md)：

5. [MCP](samples/05-mcp.md) → 6. [RAG](samples/06-rag.md)

## 第三期：上下文工程 + 多 Agent

见 [phase3.md](phase3.md)：

7. [上下文工程](samples/07-context.md) → 8. [多 Agent](samples/08-multi-agent.md)

## 基础补丁（发版前）

一至三期主干完成后，按 [baseline-patches.md](baseline-patches.md) 补齐横切缺口：

- A1 Prompt 模板化
- A2 同步接口 Token 用量
- A3 RAG 空检索拒答
- A4 Agent ReAct 流式（最小）
- A5 文档与自测

全部通过后发版 **v0.2.0 baseline**。

## 基础阶段完成标准

下列项全部达成，即视为「基础闭环」完成（对应 tag `v0.2.0`）：

| 类别 | 标准 |
| --- | --- |
| 样例闭环 | 01–08 样例均可跑通（Java + 前端 Tab + Python 对照可选） |
| Prompt | Chat / Agent / Structured 的 system prompt 外置到 `resources/prompts/`，经 `PromptLoader` 加载 |
| Token 用量 | 同步 Chat / Tools / Structured 响应含 `usage`（`TokenUsage`，提取失败为 null） |
| RAG 拒答 | 空检索时 `retrievalEmpty=true`，不编造语料外内容 |
| Agent 流式 | `GET /agent/react/stream` 可流式输出最终答案 |
| 测试 | `./gradlew test` 通过；`frontend` `tsc` 无错 |
| 文档 | [baseline-patches.md](baseline-patches.md) 验收 curl 可复现 |

未纳入基础阶段（见 [backlog](backlog.md)）：Hybrid 检索、golden 评测、Redis 持久会话、逐步 tool SSE、流式 token 累加等。

## 进阶入口

**v0.2.0 发版之后**进入第四期，只做 backlog 标注的两项进阶：

1. **Hybrid RAG** — 向量 + PostgreSQL 全文 + RRF；`POST /rag/query/compare`；样例 [09-hybrid-rag.md](samples/09-hybrid-rag.md)
2. **Agent 评测** — `classpath:eval/golden/*.json` + `POST /eval/run`；样例 [10-eval.md](samples/10-eval.md)

见 [phase4.md](phase4.md)。

## 第四期：Hybrid RAG + Agent 评测（进阶）

见 [phase4.md](phase4.md)：

9. [Hybrid RAG](samples/09-hybrid-rag.md) → 10. [Agent 评测](samples/10-eval.md)

## 第五期：持久会话 + 长期记忆（进阶）

见 [phase5.md](phase5.md)：

11. [持久会话](samples/11-persist-session.md) → 12. [长期记忆](samples/12-long-term-memory.md)

## 第六期：MCP 远端拆分 + 完整 HyDE（进阶）

见 [phase6.md](phase6.md)：

13. [MCP 远端拆分](samples/13-mcp-remote.md) → 14. [完整 HyDE](samples/14-hyde.md)

## 第七期：语义分块 + 父子文档（进阶，双分支）

见 [phase7.md](phase7.md)：

15. [语义分块](samples/15-semantic-chunk.md)（分支 7a）→ 16. [父子文档](samples/16-parent-child.md)（分支 7b）

## 第八期：自动抽记忆 + 召回策略对照（进阶，双分支）

见 [phase8.md](phase8.md)：

17. [自动抽记忆](samples/17-memory-extract.md)（分支 8a）→ 18. [召回策略对照](samples/18-memory-recall-compare.md)（分支 8b）

## 第九期：MCP Bearer 鉴权（进阶）

见 [phase9.md](phase9.md)：

19. [MCP Bearer 鉴权](samples/19-mcp-bearer.md)

基础 vs 进阶分层：

| 层次 | 范围 | 状态 |
| --- | --- | --- |
| **基础** | 最小 Agent 闭环 + MCP/RAG 入门 + 进程内上下文 + 同进程多 Agent + 基础补丁 A1–A5 | **已完成**（tag `v0.2.0`） |
| **进阶** | Hybrid、评测、持久会话、长期记忆、MCP 远端、HyDE、分块、抽记忆、MCP 鉴权等 | **第四～九期** |

## 刻意不做 / 后续候选

见 [backlog.md](backlog.md)。
