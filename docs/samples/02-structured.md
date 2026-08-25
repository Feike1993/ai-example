# 02 结构化输出

## 概念

- JSON Mode：只保证是 JSON，不保证字段
- Schema / Bean 转换：按类型解析；失败就要修或重试
- LLM 常见瑕疵：Markdown 围栏、字符串内未转义引号、前后解释文字

本仓三层策略：本地修 JSON → 重试时追加严格指令和上次错误 → 仍失败再抛错。

## 结构化输出底层逻辑

1. **目标是「可解析的数据」** — 业务要的是字段齐全的对象（如工单 JSON），不是散文；自然语言回复无法直接进下游系统。
2. **Schema / Bean 约束输出形状** — 把目标类型（字段、类型）编进提示或转换器格式；模型仍在生成文本，但被引导成符合结构的 JSON。
3. **JSON Mode ≠ 字段正确** — 只保证返回的是 JSON，不保证键名或类型正确；真正落地靠 Schema 解析与校验。
4. **LLM 输出常「差一点」** — 常见瑕疵：Markdown 围栏、未转义引号、前后解释文字。先本地修 JSON，再交给 Bean 转换。
5. **失败则带着错误重试** — 把上次解析错误写回 system、收紧指令；超过次数再抛错，避免静默脏数据进入业务。
6. **必须用 plain ChatClient** — 挂工具会混入 tool 消息，污染「只要 JSON」的假设；结构化抽取场景用不挂工具的客户端。

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/structured/ticket \
  -H 'Content-Type: application/json' \
  -d '{"text":"登录页偶尔 500，P1，标签 backend,auth"}'
```

```bash
cd python && uv run python -m ai_example.samples.structured
```

## 对照 / 拷贝

- Java：`StructuredOutputInvoker`、`JsonRepair`、`prompts/extract-ticket.st`
- 业务侧必须用 **不挂工具** 的 ChatClient
