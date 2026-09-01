# 第八期学习路径：自动抽记忆 + 召回策略对照

第七期覆盖语义分块与父子文档。第八期在第五期长期记忆之上做两项进阶，拆成双分支：

| 分支 | 样例 | 状态 |
| --- | --- | --- |
| `phase8a` | [17 自动抽记忆](samples/17-memory-extract.md) | 已交付 |
| `phase8b` | [18 召回策略对照](samples/18-memory-recall-compare.md) | **本分支交付** |

## 建议顺序

1. `POST /memory/extract`（或手工 remember）准备事实
2. `POST /memory/recall/compare` 看 topK / 阈值差异
3. `POST /memory/chat/compare` 看有无记忆答案
4. Playground：**MemoryExtract** → **MemoryCompare**

## 怎么跑（摘要）

```bash
docker compose up -d
cd java && ./gradlew bootRun

curl -s http://localhost:8080/ai-example/memory/recall/compare \
  -H 'Content-Type: application/json' \
  -d '{"query":"喜欢吃什么","userId":"demo","lowTopK":1,"highTopK":6}'
```

Python：

```bash
cd python && uv run python -m ai_example.samples.memory_recall_compare
```

## 配置

- `app.ai.memory.top-k` / `similarity-threshold`（compare 请求可覆盖）
- `app.ai.memory.extract-max-facts`（8a）

刻意不做：静默每轮抽取、Redis、看板 — 见 [backlog.md](backlog.md)。
