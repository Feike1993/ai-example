# 08 多 Agent

## 概念

- 多 Agent ≠ 多个模型账号；核心是**信息边界与交接**
- **Orchestrator**：拆任务、选择专员、汇总
- **WorkerA（调研）**：挂工具跑受限 ReAct（天气 / 加法）
- **WorkerB（执笔）**：无工具，根据材料写最终答复
- 可观测：响应里的 `agents[].steps`、错误、是否触达步数上限

本仓同进程；分布式 / 评测平台见 [backlog](../backlog.md)。

## 怎么跑

```bash
cd java && ./gradlew bootRun
```

```bash
curl -s http://localhost:8080/ai-example/multiagent/run \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"查一下北京天气，再写一句给游客的出行建议","provider":"deepseek"}'
```

Python 对照（LangGraph 多节点）：

```bash
cd python
uv run python -m ai_example.samples.multi_agent
```

## 对照 / 拷贝

- Java：`samples.multiagent`（Orchestrator + 复用 `ReactAgentLoop` / `DemoTools`）
- 配置：`app.ai.multiagent.max-orchestrator-steps` / `max-worker-steps`
- 对比第一期单 Agent：`POST /ai-example/agent/react` 把工具全挂一人

## 测试

```bash
cd java && ./gradlew test --tests MultiAgentOrchestratorTest
```
