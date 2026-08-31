# 11 持久会话

## 概念

第三期上下文样例用进程内 `Map` 存会话，**重启即丢**。持久会话只换存储：

- **短期记忆**仍是「你塞回模型窗口的消息」
- **trim / summarize** 预算策略不变
- 存储从内存换成 **PostgreSQL**（与 RAG 同库，不引 Redis）

配置 `app.ai.context.store`：

| 值 | 含义 |
| --- | --- |
| `jdbc`（默认） | 表 `chat_session_message`，重启可续聊 |
| `memory` | 原进程内 Map，便于单测 / 无 DB |

响应字段 `store` 标明当前实现。

## 怎么跑

```bash
docker compose up -d
cd java && ./gradlew bootRun
```

```bash
# 第一轮
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"persist-demo","prompt":"我叫小明，喜欢北京","strategy":"trim"}' | jq '.sessionId,.store,.content'

# 重启 Java 后再问（Postgres 仍在）
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"persist-demo","prompt":"我叫什么？","strategy":"trim"}' | jq '.content,.store'

curl -s http://localhost:8080/ai-example/context/session/persist-demo | jq '.messages'
```

Python 对照（SQLite 文件持久化）：

```bash
cd python && uv run python -m ai_example.samples.context_memory --persist /tmp/ai-example-session.db
```

## 学什么

- 持久化 ≠ 换策略；只换「消息存在哪」
- cookbook 用 PG 够讲清概念；生产可换 Redis

## 刻意不做

Redis、跨节点会话复制、精确 tokenizer — 见 [backlog](../backlog.md)。
