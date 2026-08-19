# 04 ReAct Agent Loop

## 概念

Agent = LLM + Planning + Memory + Tools。第一期 Memory 只用当轮消息列表。

循环：Perceive（用户输入）→ Reason（模型是否调用工具）→ Act（执行工具）→ Observe（把结果塞回消息）。

终止条件：

- 模型不再返回 tool_calls（任务完成）
- 达到 `maxSteps`（防止无限循环）
- 工具异常时返回错误文本，让模型降级，而不是死循环重试同一调用

**Agent vs 框架托管：**

| 方式 | 入口 | 适合 |
| --- | --- | --- |
| 显式 Loop | `POST /ai-example/agent/react` | 要审计每一步、限制步数 |
| Spring AI 自动执行 | `POST /ai-example/agent/framework` | 业务里快速接入 |
| LangGraph | `python -m ai_example.samples.react_agent` | 产业界主流图编排 |

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5","maxSteps":8}'
```

## 对照 / 拷贝

- Java：`ReactAgentLoop`（`ChatModel.call()` 只返回 tool_calls，由本类执行工具并限制步数）
- Python：`langgraph.prebuilt.create_react_agent`
