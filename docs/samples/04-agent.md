# 04 ReAct Agent Loop

## 概念

Agent = LLM + Planning + Memory + Tools。第一期 Memory 只用当轮消息列表。

循环：Perceive（用户输入）→ Reason（模型是否调用工具）→ Act（执行工具）→ Observe（把结果塞回消息）。

终止条件：

- 模型不再返回 tool_calls（任务完成）
- 达到 `maxSteps`（防止无限循环）
- 工具异常时返回错误文本，让模型降级，而不是死循环重试同一调用

## Agent Loop 底层逻辑

1. **Agent = 会多步决策的 Chat + Tools** — 相对单次 Tool Calling，Agent 在任务「未完成」时会继续 Reason → Act，直到给出最终答案或触达终止条件。
2. **Perceive → Reason → Act → Observe** — 用户输入进消息列表；模型判断要不要工具；执行工具；观察结果写回消息，再进入下一轮推理。
3. **Memory 第一期 = 当轮消息列表** — 无跨会话外存；每一步都基于不断变长的 messages，历史工具结果也在同一列表里。
4. **终止条件要显式** — 无 tool_calls（任务完成）、达到 `maxSteps`（防死循环）、工具错误文本让模型降级，而不是无限重试同一调用。
5. **显式 Loop vs Framework** — 手写 Loop（如 `ReactAgentLoop`）可审计每一步；`ChatClient.tools` 托管适合业务快速接入，轨迹不如显式清晰。
6. **Agent ≠ 工作流** — 工作流路径预先写死；Agent 下一步由模型选择。开放任务用 Agent，确定性任务用工作流。

**Agent vs 框架托管：**

| 方式 | 入口 | 适合 |
| --- | --- | --- |
| 显式 Loop | `POST /ai-example/agent/react` | 要审计每一步、限制步数；响应含 `usage` |
| 显式 Loop SSE | `GET /ai-example/agent/react/stream` | 多跳 `tool_call`/`tool_result` + 终答 + `usage`/`done` |
| Spring AI 自动执行 | `POST /ai-example/agent/framework` | 业务里快速接入 |
| LangGraph | `python -m ai_example.samples.react_agent` | 产业界主流图编排 |

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5","maxSteps":8}' | jq '.steps, .usage, .finalAnswer'

# SSE：逐步 tool 事件 →（可选聚合 steps）→ 终答 → usage → done
curl -N 'http://localhost:8080/ai-example/agent/react/stream?prompt=北京天气怎么样？再算%203%2B5&maxSteps=8'
```

流式与同步共用同一完整多跳循环（第十期）；细节见 [20-agent-tool-sse.md](20-agent-tool-sse.md)、[21-stream-token-usage.md](21-stream-token-usage.md)。

## SSE 与 boundedElastic（为何这样包）

| 类型 | 语义 | 本样例用法 |
| --- | --- | --- |
| **Flux.create** | 主动向 sink 推多个 SSE | `reactStream`：边跑 Loop 边 `onToolCall` / `onToolResult` |
| **subscribeOn(boundedElastic)** | 阻塞型多跳丢到弹性池 | 避免占住 SSE / Netty 事件循环 |

A4 曾用「先 `prepareStream` 再只流终答」；第十期改为 `Progress` 回调 + 同一 `run()`，可选仍发聚合 `event:steps` 兼容旧客户端。

## 对照 / 拷贝

- Java：`ReactAgentLoop`（同步 `run` + 可选 `Progress`）；`AgentSampleService.reactStream` → `GET /agent/react/stream`
- Python：`langgraph.prebuilt.create_react_agent`；离线对照 `run_observable_demo`（逐步事件 + 假 usage）
