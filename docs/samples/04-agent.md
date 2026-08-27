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
| 显式 Loop | `POST /ai-example/agent/react` | 要审计每一步、限制步数 |
| 显式 Loop SSE | `GET /ai-example/agent/react/stream` | 工具轮同步后流式终答 |
| Spring AI 自动执行 | `POST /ai-example/agent/framework` | 业务里快速接入 |
| LangGraph | `python -m ai_example.samples.react_agent` | 产业界主流图编排 |

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5","maxSteps":8}'

# 最小 SSE：首包 event:steps，后续为 finalAnswer 增量（不做逐步 tool SSE）
curl -N 'http://localhost:8080/ai-example/agent/react/stream?prompt=北京天气怎么样？再算%203%2B5&maxSteps=8'
```

流式路径在完成一轮 tool 执行后用不挂 tools 的 stream 生成终答（并行 tool_calls 一轮即可覆盖常见演示）；需要「工具→再工具」多轮时用同步 `/react`。

## SSE 与 Mono（为何这样包）

| 类型 | 语义 | 本样例用法 |
| --- | --- | --- |
| **Mono** | 最多发出 **1** 个元素就结束 | `prepareReactStream`：整段工具轮准备 → 一份 `StreamPrep` |
| **Flux** | 发出 **0～N** 个元素 | SSE 响应、终答 token 增量 |

底层都是 **订阅驱动**：没人 `subscribe`，流水线不跑。`Mono.fromCallable(…)` 把同步的 `prepareStream`（多次 `chatModel.call` + 执行工具）变成「被订阅时才执行」的延迟计算；再 `.subscribeOn(Schedulers.boundedElastic())` 丢到弹性线程池，避免占住处理 SSE 的事件循环线程。Controller 用 `flatMapMany`：拿到那一份 `StreamPrep` 后，再拼成「先 `event:steps`、后 answer」的 `Flux<ServerSentEvent>`。

不选 `Flux.fromCallable`：准备结果不是一串元素；且 Reactor 3.8 起 Flux 侧已不再提供 `fromCallable`，阻塞型单次计算惯例用 `Mono.fromCallable`。

## 对照 / 拷贝

- Java：`ReactAgentLoop`（`ChatModel.call()` 只返回 tool_calls，由本类执行工具并限制步数）；流式见 `prepareStream` + `Mono.fromCallable` + `GET /agent/react/stream`
- Python：`langgraph.prebuilt.create_react_agent`
