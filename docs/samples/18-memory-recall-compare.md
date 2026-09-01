# 18 召回策略对照

## 概念

同一问题下并排看清：

| 策略 | 含义 |
| --- | --- |
| `lowTopK` | 较小 topK，召回更窄 |
| `highTopK` | 较大 topK，召回更宽 |
| `withThreshold` | 大 topK + 相似度阈值过滤 |

以及 **有记忆 chat** vs **无记忆纯 Chat**（不查库）答案差异。

- `POST /memory/recall/compare`：默认只比 **sources**，不三次生成，控成本
- `POST /memory/chat/compare`：默认生成两套答案（`withMemory` / `withoutMemory`）
- 阈值依赖 Document `score`；若向量库未带回 score，则仅 topK 生效（见响应说明）

## 怎么跑

```bash
# 先 remember 若干事实，再：
curl -s http://localhost:8080/ai-example/memory/recall/compare \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"小明喜欢什么？",
    "userId":"demo",
    "lowTopK":1,
    "highTopK":8,
    "similarityThreshold":0.5
  }' | jq .

curl -s http://localhost:8080/ai-example/memory/chat/compare \
  -H 'Content-Type: application/json' \
  -d '{
    "prompt":"根据记忆，我喜欢吃什么？",
    "userId":"demo",
    "provider":"deepseek"
  }' | jq '{with: .withMemory.answer, without: .withoutMemory.answer}'
```

## 前端 / Python

- Playground：**MemoryCompare**
- Python：`uv run python -m ai_example.samples.memory_recall_compare`

## 刻意不做

评测看板、分布式记忆、查询改写链。
