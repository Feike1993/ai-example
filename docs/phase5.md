# 第五期学习路径：持久会话 + 长期记忆

第四期覆盖 Hybrid RAG 与 golden 评测。第五期在此之上做两块与「记忆」相关的进阶：

1. **持久会话**（[samples/11-persist-session.md](samples/11-persist-session.md)）— 第三期进程内 Map 升级为 PostgreSQL
2. **长期记忆**（[samples/12-long-term-memory.md](samples/12-long-term-memory.md)）— pgvector 独立 corpus 写入 / 召回

建议顺序：先持久会话（理解「短期窗口换存储」），再长期记忆（理解「跨会话事实库」）。

## 建议顺序

1. **持久会话**
   - 短期记忆 = 塞回窗口的消息；trim / summarize 策略不变
   - `app.ai.context.store=jdbc|memory`；默认 jdbc，与 RAG 同库
2. **长期记忆**
   - 与 RAG 演示语料隔离（`corpus=long-term-memory`）
   - remember → recall → chat with memory

## 怎么跑（摘要）

```bash
docker compose up -d
cd java && ./gradlew bootRun
cd frontend && pnpm install && pnpm dev

# 持久会话：重启后同一 sessionId 仍可续聊
curl -s http://localhost:8080/ai-example/context/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"persist-1","prompt":"我叫小明","strategy":"trim"}'

# 长期记忆
curl -s -X POST http://localhost:8080/ai-example/memory/remember \
  -H 'Content-Type: application/json' \
  -d '{"text":"用户喜欢北京烤鸭","userId":"demo"}'
curl -s -X POST http://localhost:8080/ai-example/memory/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"我喜欢吃什么？","userId":"demo"}'
```

Python 对照：

```bash
cd python && uv sync --group dev
uv run python -m ai_example.samples.context_memory
uv run python -m ai_example.samples.long_term_memory
```

刻意不做：Redis、MCP 第二进程、完整 HyDE、语义分块 — 见 [backlog.md](backlog.md)。
