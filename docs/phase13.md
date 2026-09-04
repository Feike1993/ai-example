# 第十三期：Playground 进阶侧栏整理

第十二期交付记忆×检索闭环后，进阶侧栏已有 **15** 个平铺项，扫读成本高。第十三期只做 **信息架构（IA）**，**不**新增学习样例或 API。

## 目标

进阶区按主题常显分组，组内仍是原有独立 Panel；文档号与 Playground `index` 不变。

| 组 | 样例 |
| --- | --- |
| 检索进阶 | Hybrid RAG、HyDE、语义分块、父子文档、RAG Citation、记忆辅助改写、RAG vs 记忆 |
| 记忆 | 长期记忆、自动抽记忆、召回对照 |
| MCP | MCP Bearer |
| Agent·可观测 | Agent 逐步 SSE、Usage 累加 |
| 质量与护栏 | 评测、输出护栏 |

## 怎么验证

```bash
cd frontend && pnpm dev
# 切换「进阶」：应见五组标题；点各组样例仍打开原面板

cd frontend && npx tsc --noEmit
```

根索引：`GET /` → `advanced.phase=13`（样例 map 无新 key）。

## 宣传图（底层逻辑）

宣传页基础 Journey 之后有「进阶 · 五组逻辑」图墙（锚点 `#advanced-groups`）。静态图路径：

| 组 | 文件 |
| --- | --- |
| 检索进阶 | [`frontend/public/promo/advanced/rag.png`](../frontend/public/promo/advanced/rag.png) |
| 记忆 | [`frontend/public/promo/advanced/memory.png`](../frontend/public/promo/advanced/memory.png) |
| MCP | [`frontend/public/promo/advanced/mcp.png`](../frontend/public/promo/advanced/mcp.png) |
| Agent·可观测 | [`frontend/public/promo/advanced/agent-obs.png`](../frontend/public/promo/advanced/agent-obs.png) |
| 质量与护栏 | [`frontend/public/promo/advanced/quality.png`](../frontend/public/promo/advanced/quality.png) |

```bash
cd frontend && pnpm dev
# 打开 /promo.html#advanced-groups
```

## 刻意不做

- 不删样例、不合并枢纽 Panel、不重排文档号 / API
- 不为 15 个进阶样例各做 SampleSection 动效
- 不加 Redis、不新开学习主题 — 见 [backlog.md](backlog.md)
