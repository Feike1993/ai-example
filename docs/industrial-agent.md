# 工业级第八阶段：生产 Agent 人工验证

对照本机演示：**工具策略可查询、无 LLM 探针、审计带工具名、步骤时间线、Grafana Agent 指标**。默认不打 Chat LLM，不进 CI。

这是工业级第八阶段，不是课程「第八期 自动抽记忆」。教学 `/agent/react` 不是本阶段入口。第六 / 七阶段仍见 [industrial-ha.md](industrial-ha.md)、[industrial-ops.md](industrial-ops.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 8a 策略可查 | `GET /agent/tools`：alice 无 `rebuild_index`，admin 有。`POST /agent/tool-probe` 不调模型、不真执行 |
| 8a 探针 | alice 探针 → `denied:true`；admin → `allowed:true`。工业页安全面板能点「探针 rebuild_index」 |
| 8b 审计 | 探针后 `GET /audit` 有 `action=agent.tool`、`toolName=rebuild_index`、`denied=true`（alice）。无问题原文、无参数明文 |
| 8c 时间线 | `GET /ops/runs/{runId}` 跨租户 / 过期 **404** `run_not_found`。真 Agent 本机选跑后可观测面板能加载步骤 |
| 8d Grafana | 看板有「Agent 次数」「工具拒绝」；打几次探针后能量到 `prod_tool_denied` |
| 8e E2E | 默认 Playwright 覆盖 alice/admin 探针与工具列表，**不**打 `/agent/stream` |

## 前置

与第七阶段相同：`.env` 已设 `PRODUCTION_KEK`；Compose 或本机 Java + Redis + Postgres。探针本身不需要 Embedding / Chat Key。

## 1. 无 LLM 探针

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)
ADMIN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -r .token)

curl -sS http://127.0.0.1:8080/ai-example/api/v1/agent/tools \
  -H "Authorization: Bearer $TOKEN" | jq .
curl -sS -X POST http://127.0.0.1:8080/ai-example/api/v1/agent/tool-probe \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tool":"rebuild_index"}' | jq .
curl -sS -X POST http://127.0.0.1:8080/ai-example/api/v1/agent/tool-probe \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"tool":"rebuild_index"}' | jq .
```

期望：alice `tools` 不含 `rebuild_index`，探针 `denied=true`；admin 相反且 `allowed=true`。admin 探针**不会**重建语料。

工业页：alice 登录 → 安全 → 允许的工具 → 探针 rebuild_index → 提示已拒绝 → 刷新审计看到工具列。

## 2. 审计列

```bash
curl -sS 'http://127.0.0.1:8080/ai-example/api/v1/audit?limit=20' \
  -H "Authorization: Bearer $TOKEN" | jq '.[] | select(.action=="agent.tool")'
```

期望：`toolName` 为 `rebuild_index`，`denied` 为 true，没有问句原文。

## 3. 步骤时间线（无 Key 只测 404）

```bash
curl -sS -D - -o /tmp/run.json \
  http://127.0.0.1:8080/ai-example/api/v1/ops/runs/missing \
  -H "Authorization: Bearer $TOKEN"
```

期望：**404** `run_not_found`。过期或别人租户的 runId 同样 404，不要用 410 暴露存在性。

本机选跑（已配 Chat Key）：工业页切 Agent 流式提问 → 复制 meta `runId` → 可观测面板加载时间线，步骤应与直播一致。窗口过了仍是 404。

## 4. Grafana

探针几次后打开 `http://localhost:3000` 看板「工业级 /api/v1」，应有 Agent 次数、工具拒绝。匿名 `/actuator/prometheus` 仍不是 200。

## 5. 可选单测

```bash
cd java
./gradlew test --tests ProductionJwtMvcTest --tests ToolPolicyTest --tests ProductionToolsTest \
  --tests ProductionRunTimelineServiceTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
pnpm test:e2e
```

默认 Playwright **不**打 `GET /agent/stream`。

## 刻意不做

- 教学第八期记忆抽取；`search_kb` 接 queryExpansion
- Agent 流式 Idempotency-Key、会话内永久存步骤、逐 token 终答
- YAML 企业 ACL / Vault / 真实外部工具 / LangGraph
- 把 k6 / HA / Grafana 打进 CI
