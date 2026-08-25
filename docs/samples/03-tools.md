# 03 Tool Calling

## 概念

- Function Calling：模型输出「要调哪个函数 + 参数」，由你的代码执行
- 工具用 JSON Schema 描述；粒度优先原子操作，组合逻辑放 Agent Loop
- 工具失败要返回错误字符串让模型改主意，而不是直接把进程打崩
- 不要把全部工具挂到全局 Client：结构化输出场景会被 tool 消息污染

## Tool Calling 底层逻辑

1. **模型不会真的「调用 API」** — 它只生成结构化意图：函数名 + 参数；真正执行发生在你的进程里，由应用代码完成。
2. **工具用 Schema 挂进上下文** — `@Tool` / JSON Schema（名、描述、参数）进入提示；模型据此决定是否调用、调哪个、传什么参数。
3. **一轮典型协议** — 用户话 → 模型返回 tool_calls → 应用执行 → 把结果作为 tool 消息回填 → 模型再生成自然语言答案（本样例：天气 + 计算器）。
4. **粒度要原子** — 单个工具只做一件事；多步编排留给 Agent Loop，避免一个巨型万能工具难调试、难复用。
5. **失败返回错误字符串** — 让模型改参数或换策略，而不是把服务进程打崩；错误文本本身成为下一轮上下文。
6. **按请求挂载，不全局一把梭** — 结构化抽取等场景不要挂工具，避免消息形态被 tool 调用污染；工具按场景按请求挂载。

## 怎么跑

```bash
curl -s http://localhost:8080/ai-example/tools \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5"}'
```

```bash
cd python && uv run python -m ai_example.samples.tools
```

## 对照 / 拷贝

- Java：`DemoTools`（`@Tool`）+ `ToolSampleService.tools(demoTools)`
- Python：手写 OpenAI `tools` + 执行循环，便于看清协议
