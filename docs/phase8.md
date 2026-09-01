# 第八期学习路径：自动抽记忆 + 召回策略对照

第七期覆盖语义分块与父子文档。第八期在第五期长期记忆之上做两项进阶，拆成双分支：

| 分支 | 样例 | 状态 |
| --- | --- | --- |
| `phase8a` | [17 自动抽记忆](samples/17-memory-extract.md) | **本分支交付** |
| `phase8b` | [18 召回策略对照](samples/18-memory-recall-compare.md) | 待 8a 合入后再做 |

## 建议顺序（8a）

1. `POST /memory/extract`（粘贴对话或传 `sessionId`）
2. `POST /memory/recall` 验证写入
3. Playground 进阶 Tab **MemoryExtract**

## 怎么跑（摘要）

```bash
docker compose up -d
cd java && ./gradlew bootRun

curl -s http://localhost:8080/ai-example/memory/extract \
  -H 'Content-Type: application/json' \
  -d '{"userId":"demo","provider":"deepseek","messages":[
    {"role":"user","content":"我叫小明，住在杭州"},
    {"role":"assistant","content":"好的，记住了。"}
  ]}'
```

Python：

```bash
cd python && uv run python -m ai_example.samples.memory_extract
```

刻意不做：每轮 chat 静默抽取、Redis、看板 — 见 [backlog.md](backlog.md)。
