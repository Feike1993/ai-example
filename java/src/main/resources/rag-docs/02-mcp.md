# MCP 是什么

MCP（Model Context Protocol）是工具与资源的**接入协议**，好比 USB-C：

- **Function Calling**：LLM 能力——输出「要调哪个函数」
- **MCP**：连接标准——Host / Client / Server，JSON-RPC
- **Agent**：系统概念——Loop + Memory + Tools

本仓第二期把第一期的 DemoTools / CpkTools 注册到 MCP Server（Streamable HTTP，端点 `/mcp`）。
传输对比：stdio 适合本地子进程；Streamable HTTP 适合远程与生产。旧的纯 SSE 传输在 Spring AI 2.0 已弃用。
