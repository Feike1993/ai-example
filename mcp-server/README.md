# ai-example MCP Server（独立进程）

第六期样例：仅暴露 **MCP Streamable HTTP**，默认端口 **8081**，协议端点 `/mcp`。  
第九期起：`/mcp` 需 `Authorization: Bearer`（与主应用共享 `MCP_BEARER_TOKEN`，默认 `dev-mcp-token`）。

主应用（`java/`）以 MCP Client 连接本进程，见 [docs/samples/13-mcp-remote.md](../docs/samples/13-mcp-remote.md)、[19-mcp-bearer.md](../docs/samples/19-mcp-bearer.md)。

## 跑起来

### 宿主机开发

```bash
cd mcp-server
# 可选：export MCP_BEARER_TOKEN=dev-mcp-token
./gradlew bootRun
```

### Docker Compose

完整系统由仓库根目录统一编排，独立 MCP Server 默认不向宿主机暴露端口：

```bash
cd ..
docker compose up -d --build --wait
```

需要从宿主机直接调试协议端点时，叠加开发覆盖文件：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait
```

容器自身健康检查：

```bash
curl -fsS http://localhost:8081/actuator/health/liveness
```

主应用 `GET /ai-example/mcp/tools`（remote）能列出 `getWeather`、`add`，则额外说明 Client、Compose 网络、Bearer 和 MCP 初始化链路均正常。

无凭证直连应 401：

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{}'
```

## 工具

与主应用 `DemoTools` 对齐：`getWeather`、`add`（无 CPK，保持旁进程极简）。

## 刻意不做

OAuth2、多租户、与主应用共享 jar — cookbook 用拷贝 DemoTools + 共享密钥即可。
