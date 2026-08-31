# 13 MCP 远端拆分

## 概念

第二期同进程样例：主应用既当 MCP Server，又把 `ToolCallbackProvider` **直接注入** ChatClient，避免 Client 启动期连自己。

第六期拆成：

| 进程 | 角色 | 端口 |
| --- | --- | --- |
| `mcp-server/` | MCP Server（STREAMABLE） | 8081，`/mcp` |
| `java/` 主应用 | MCP Client + Chat 样例 | 8080，`/ai-example` |

配置 `app.ai.mcp.mode`（**启动初始值**；运行时可用面板或 `PUT /mcp/mode` 切换，内存不持久化）：

| 值 | 含义 |
| --- | --- |
| `remote`（默认） | Client 连 `http://localhost:8081/mcp`；主应用不启 MCP Server HTTP |
| `inprocess` | 同进程 `mcpServerTools` 直挂 ChatClient（无需旁进程） |

主应用默认 `spring.ai.mcp.client.initialized=false`，**启动不强连 8081**；remote 下列工具 / 聊天时再懒握手，连不上返回 503。

## 怎么跑

```bash
# 终端 1（remote 模式需要）
cd mcp-server && ./gradlew bootRun

# 终端 2（8081 未起也能启动主应用；可先切 inprocess）
cd java && ./gradlew bootRun
```

```bash
curl -s http://localhost:8080/ai-example/mcp/tools | jq .
# 运行时切到同进程（无需 8081）
curl -s -X PUT http://localhost:8080/ai-example/mcp/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"inprocess"}' | jq .
curl -s http://localhost:8080/ai-example/mcp/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用工具查北京天气","provider":"deepseek"}' | jq '.mode,.toolNames,.content'
```

期望 remote 且 8081 已起：`mode=remote`，`toolNames` 含 `getWeather` / `add`。

也可用环境变量设初始模式：

```bash
MCP_MODE=inprocess
```

## 学什么

- Host / Client / Server 真正分进程
- Streamable HTTP URL 是协议入口，不是业务 `/mcp/chat`
- cookbook 本地明文；生产务必鉴权

## 刻意不做

鉴权、多 Server 注册中心 — 见 [backlog](../backlog.md)。
