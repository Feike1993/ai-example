# 07 上下文工程

## 概念

- 模型本身**没有**跨请求记忆；「记得上文」= 客户端把历史再塞进本次窗口
- **trim**：超预算丢掉最旧轮次，保留 system + 最近 K 轮
- **summarize**：旧轮次压成短摘要，再拼最近轮次（多一次 LLM 调用）
- **Lost in the Middle**：中间段落更容易被忽略；预算策略会改变「中间」落在哪
- Harness 六层（信息边界 / 工具 / 编排 / 记忆 / 评估 / 约束）本仓只落 **记忆 + 预算约束**；其余见 [backlog](../backlog.md)

本仓会话默认存 **PostgreSQL**（`app.ai.context.store=jdbc`），重启可续聊；`store=memory` 为进程内 Map。详见 [11-persist-session.md](11-persist-session.md)。Redis 仍见 [backlog](../backlog.md)。

## 怎么跑

```bash
cd java && ./gradlew bootRun
```

```bash
# 多轮（同一 sessionId）；strategy=trim|summarize
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","prompt":"我叫小明，喜欢北京","provider":"deepseek","strategy":"trim"}'

curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo-1","prompt":"我叫什么？喜欢哪个城市？","provider":"deepseek","strategy":"trim"}'

curl -s http://localhost:8080/ai-example/context/session/demo-1
```

Python 对照：

```bash
cd python
uv run python -m ai_example.samples.context_memory
```

## 对照 / 拷贝

- Java：`samples.context`（`InMemoryChatSessionStore` + trim/summarize）
- 配置：`app.ai.context.max-messages` / `token-budget`
- 生产持久化会话、向量长期记忆：见 [backlog](../backlog.md)

## 测试

```bash
cd java && ./gradlew test --tests ContextBudgetTest
```
