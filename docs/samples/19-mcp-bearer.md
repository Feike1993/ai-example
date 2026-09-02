# 19 MCP Bearer 鉴权

## 概念

第六期样例 [13](13-mcp-remote.md) 本地明文连 `mcp-server:8081`。本样例补上 **共享密钥 Bearer**：

| 进程 | 行为 |
| --- | --- |
| `mcp-server/` | Filter 校验 `Authorization: Bearer <token>`；无/错 → **401** |
| 主应用 Client | remote 请求自动带同一 token（`MCP_BEARER_TOKEN`） |
| `mode=inprocess` | 不走 HTTP，**不要求** Bearer |

默认 token：`dev-mcp-token`（两端一致即可 `bootRun`）。

## 怎么跑

```bash
# 终端 1
cd mcp-server && ./gradlew bootRun

# 无凭证 → 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# 有凭证（仍可能因 MCP 握手细节返回其它码，但不应是 401）
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8081/mcp \
  -H 'Authorization: Bearer dev-mcp-token' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# 终端 2：主应用 remote（Client 自动带 Bearer）
cd java && ./gradlew bootRun
curl -s http://localhost:8080/ai-example/mcp/tools | jq .
```

自定义密钥（两端相同）：

```bash
export MCP_BEARER_TOKEN=my-secret
```

## 学什么

- Streamable HTTP 上如何挂最小鉴权
- Client 与 Server **同密钥**约定；401 时优先查 env 是否一致
- inprocess 对照：鉴权只拦网络面

## 刻意不做

OAuth2 / JWT 发卡、多租户 ACL、完整 Spring Security 登录页 — 见 [backlog](../backlog.md)。
