# 第十期学习路径：可观测流式

第九期覆盖 MCP Bearer。第十期在 A4「仅终答 SSE」之上补两项可观测能力：

| 样例 | 焦点 |
| --- | --- |
| [20 Agent 逐步 tool SSE](samples/20-agent-tool-sse.md) | `tool_call` / `tool_result` 实时推送 |
| [21 流式 TokenUsage 累加](samples/21-stream-token-usage.md) | 多轮 LLM 用量合计 |

## 建议顺序

1. `POST /agent/react` 看 `steps` + `usage`
2. `GET /agent/react/stream` 看逐步 SSE + 终答 + `usage` / `done`
3. Playground 进阶：**AgentToolSse** → **AgentUsage**

## 怎么跑（摘要）

```bash
cd java && ./gradlew bootRun

curl -N 'http://localhost:8080/ai-example/agent/react/stream?prompt=北京天气怎么样？再算%203+5&provider=deepseek'
```

刻意不做：OTLP、评测看板、流式逐 token 精确计费 SDK — 见 [backlog.md](backlog.md)。
