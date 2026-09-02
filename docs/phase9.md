# 第九期学习路径：MCP Bearer 鉴权

第八期覆盖自动抽记忆与召回对照。第九期在第六期 MCP 远端拆分之上补 **共享密钥 Bearer**（学习形态）。

| 样例 | 状态 |
| --- | --- |
| [19 MCP Bearer 鉴权](samples/19-mcp-bearer.md) | **本分支交付** |

## 建议顺序

1. 起 `mcp-server`（默认校验 `Authorization: Bearer`）
2. 无 token curl `/mcp` → **401**
3. 主应用 remote 模式列工具（Client 自动带同密钥）
4. Playground 进阶 Tab **MCP Bearer**（或基础区 **MCP**）：remote 提示两端同 `MCP_BEARER_TOKEN`

## 怎么跑（摘要）

```bash
# 两端默认 MCP_BEARER_TOKEN=dev-mcp-token，可不显式 export
cd mcp-server && ./gradlew bootRun
cd java && ./gradlew bootRun
```

刻意不做：OAuth2、多用户 ACL、K8s Secret — 见 [backlog.md](backlog.md)。
