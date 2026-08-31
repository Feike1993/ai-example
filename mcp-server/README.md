# ai-example MCP Server（独立进程）

第六期样例：仅暴露 **MCP Streamable HTTP**，默认端口 **8081**，协议端点 `/mcp`。

主应用（`java/`）以 MCP Client 连接本进程，见 [docs/samples/13-mcp-remote.md](../docs/samples/13-mcp-remote.md)。

## 跑起来

```bash
cd mcp-server
./gradlew bootRun
```

健康检查（协议层）：主应用 `GET /ai-example/mcp/tools` 能列出 `getWeather`、`add` 即说明 Client 已连上。

## 工具

与主应用 `DemoTools` 对齐：`getWeather`、`add`（无 CPK，保持旁进程极简）。

## 刻意不做

鉴权、多租户、与主应用共享 jar — cookbook 用拷贝 DemoTools 即可。
