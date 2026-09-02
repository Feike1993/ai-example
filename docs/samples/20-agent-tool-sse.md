# 20 Agent 逐步 tool SSE

## 概念

A4 仅在工具轮**全部结束后**推 `event:steps`，再流式终答。本样例改为循环中实时推送：

| 事件 | 含义 |
| --- | --- |
| `tool_call` | 模型请求调用某工具（含 args） |
| `tool_result` | 本地执行结果 |
| `message`（默认） | 终答增量或整段 |
| `usage` | token 合计（见样例 21） |
| `done` | `{ reachedMaxSteps }` |

流式路径与同步 `POST /agent/react` 一样支持**多跳**工具（修 A4 `prepareStream` 只跑一轮的限制）。

## 怎么跑

```bash
curl -N 'http://localhost:8080/ai-example/agent/react/stream?prompt=北京天气怎么样？再算%203+5'
```

Playground：进阶 Tab **AgentToolSse**（传输选「SSE 逐步」）。

## 刻意不做

逐步取消、OTLP — 见 [backlog](../backlog.md)。
