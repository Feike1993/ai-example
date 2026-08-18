# 02 结构化输出

## 概念

- JSON Mode：只保证是 JSON，不保证字段
- Schema / Bean 转换：按类型解析；失败就要修或重试
- LLM 常见瑕疵：Markdown 围栏、字符串内未转义引号、前后解释文字

本仓三层策略：本地修 JSON → 重试时追加严格指令和上次错误 → 仍失败再抛错。

## 怎么跑

```bash
curl -s http://localhost:8080/api/samples/structured/ticket \
  -H 'Content-Type: application/json' \
  -d '{"text":"登录页偶尔 500，P1，标签 backend,auth"}'
```

```bash
cd python && uv run python -m ai_example.samples.structured
```

## 对照 / 拷贝

- Java：`StructuredOutputInvoker`、`JsonRepair`、`prompts/extract-ticket.st`
- 业务侧必须用 **不挂工具** 的 ChatClient
