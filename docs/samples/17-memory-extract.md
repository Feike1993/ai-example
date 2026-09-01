# 17 自动抽记忆

## 概念

样例 12 是 **手工 remember**。本样例用一次 Chat 从对话中抽出短事实句，再走现有 `remember`（精确去重 / 相似合并）。

- 显式 `POST /memory/extract`，**不**在 `/context/chat` 里静默写入
- 可传 `messages`，或只传 `sessionId`（从 `ChatSessionStore` 拉快照）
- corpus 仍为 `long-term-memory`，按 `userId` 隔离

## 怎么跑

```bash
docker compose up -d
cd java && ./gradlew bootRun

curl -s http://localhost:8080/ai-example/memory/extract \
  -H 'Content-Type: application/json' \
  -d '{
    "userId":"demo",
    "sessionId":"extract-demo",
    "provider":"deepseek",
    "messages":[
      {"role":"user","content":"我叫小明，喜欢北京烤鸭，住在杭州"},
      {"role":"assistant","content":"已记下你的偏好。"}
    ]
  }' | jq .

curl -s http://localhost:8080/ai-example/memory/recall \
  -H 'Content-Type: application/json' \
  -d '{"query":"小明住哪？","userId":"demo"}' | jq .
```

从已有上下文会话抽取：

```bash
# 先 POST /context/chat 产生 sessionId，再：
curl -s http://localhost:8080/ai-example/memory/extract \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<id>","userId":"demo","provider":"deepseek"}'
```

## 学什么

- 抽取 vs 手写：LLM 只负责候选事实，持久化仍走 remember 契约
- 失败降级：解析失败返回空 facts，不拖垮接口
- `extract-max-facts` 限制单次写入量

## 刻意不做

静默每轮抽取、召回策略对照（见 [18](18-memory-recall-compare.md)）— [backlog](../backlog.md)。
