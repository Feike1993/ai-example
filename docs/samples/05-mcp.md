# 05 MCP

## 概念

- **Function Calling**：LLM 的能力（输出要调哪个函数）
- **MCP**：工具接入的协议标准（像 USB-C）
- **Agent**：系统概念（Loop + Memory + Tools）

关系：Function Calling → Prompt 意图 → MCP 连接 → Skills/编排。

传输：

- **stdio**：本地子进程，适合开发机 / IDE
- **Streamable HTTP**：远程/生产默认（Spring AI 2.0；旧 SSE 传输已弃用）

## 怎么跑

先启动 Java：

```bash
cd java && ./gradlew bootRun
```

```bash
curl -s http://localhost:8080/ai-example/mcp/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5","provider":"deepseek"}'
```

响应里的 `toolNames` 来自 MCP Server 注册表（同进程样例复用该 `ToolCallbackProvider`），对比第一期 `POST /ai-example/tools`（本地 `@Tool`）。

也可：`GET /ai-example/mcp/tools`。MCP 协议端点：`/ai-example/mcp`（Streamable HTTP）。

Python 对照（stdio Server + Client）：

```bash
cd python
uv run python -m ai_example.samples.mcp_client
```

## 对照 / 拷贝

- Java：`samples.mcp`（`McpToolConfiguration` 注册 Tools；`McpSampleService` 挂工具聊天）
- 业务里通常 **MCP Server 独立部署**，业务应用只做 Client；本仓为了学习把 Server 暴露 + 样例聊天放同一进程（并关闭 `spring.ai.mcp.client`，避免启动期连自己）
- 不要把 MCP 工具和结构化输出混在同一个 ChatClient 上

## 测试

```bash
cd java && ./gradlew test --tests McpToolConfigurationTest
```
