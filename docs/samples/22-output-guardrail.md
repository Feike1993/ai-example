# 22 输出护栏

## 概念

护栏 = **确定性规则**在 LLM 前后拦截，失败短路、不静默改写。本样例三层：

| 阶段 | 名称 | 行为 |
| --- | --- | --- |
| 输入 | `input_deny` | 命中 `app.ai.guardrail.deny-words` → 拒答，不调模型 |
| 生成 | （调用 Chat） | 通过后才调 LLM |
| 输出 | `output_deny` | 回复命中词表 → 拒答 |
| 可选 | `structure` | `requireStructured=true` 时强制 `{safe,content}` 信封；解析失败或 `safe=false` → 拒答 |

响应带回 `checks[]`（`name` / `passed` / `detail`）、`blocked`、`blockStage`，便于对照哪一层拦住。

## 怎么跑

```bash
# 正常
curl -s http://localhost:8080/ai-example/guardrail/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用一句话介绍护栏"}' | jq '.blocked,.checks,.answer'

# 输入命中演示词（默认含「违禁演示词」）
curl -s http://localhost:8080/ai-example/guardrail/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"请解释违禁演示词是什么"}' | jq '.blocked,.blockStage,.checks'
```

Playground：进阶 Tab **Guardrail**。

## 刻意不做

第三方内容安全 API、越狱红队、流式途中拦截 — 见 [backlog](../backlog.md)。
