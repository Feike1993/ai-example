# 21 流式 / 多轮 TokenUsage 累加

## 概念

A2 只在**单次**同步 Chat/Tools 响应带 `usage`。Agent 多轮 `ChatModel.call` 需累加：

- 同步 `POST /agent/react`：`Trace.usage` + `usageCalls`
- 流式：`event: usage`（最终合计，含 `calls`）

终答若来自已完成的同步 call，completion 计入该轮；若网关未返回 usage 则对应字段为 null（不编造）。

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5","provider":"deepseek"}' \
  | jq '.usage, .usageCalls, .steps | length'
```

Playground：进阶 Tab **AgentUsage**。

## 刻意不做

流式逐 token 精确计费 SDK — 见 [backlog](../backlog.md)。
