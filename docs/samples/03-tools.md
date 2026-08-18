# 03 Tool Calling

## 概念

- Function Calling：模型输出「要调哪个函数 + 参数」，由你的代码执行
- 工具用 JSON Schema 描述；粒度优先原子操作，组合逻辑放 Agent Loop
- 工具失败要返回错误字符串让模型改主意，而不是直接把进程打崩
- 不要把全部工具挂到全局 Client：结构化输出场景会被 tool 消息污染

## 怎么跑

```bash
curl -s http://localhost:8080/api/samples/tools \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5"}'
```

```bash
cd python && uv run python -m ai_example.samples.tools
```

## 对照 / 拷贝

- Java：`DemoTools`（`@Tool`）+ `ToolSampleService.tools(demoTools)`
- Python：手写 OpenAI `tools` + 执行循环，便于看清协议
