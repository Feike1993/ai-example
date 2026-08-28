# 10 Agent 评测

## 概念

Agent 上线前需要 **golden set**：固定输入 + 可重复断言，量化「真的跑对了吗」。本仓实现最小 **harness 评测层**：

- 用例：`java/src/main/resources/eval/golden/*.json`
- 执行：`POST /eval/run` 直接调用现有 `ToolSampleService` / `AgentSampleService` / `RagSampleService` / `MultiAgentSampleService`（非 HTTP 自调用）
- 报告：通过率、单条耗时、步数、toolFailures、可选 `usageSummary`

## 用例 schema

```json
{
  "id": "tools-weather-beijing",
  "target": "tools",
  "prompt": "北京今天天气怎么样？",
  "mustContain": ["北京", "25"],
  "mustNotContain": [],
  "maxSteps": 8,
  "expectSources": true,
  "expectToolName": "getWeather"
}
```

| 字段 | 说明 |
| --- | --- |
| `target` | `tools` \| `agentReact` \| `rag` \| `multiagent` |
| `mustContain` | 答案须全部包含的子串 |
| `mustNotContain` | 答案不得包含 |
| `maxSteps` | Agent 类步数上限（可选） |
| `expectSources` | RAG 类要求 sources 非空 |
| `expectToolName` | Agent 类要求轨迹中出现工具名 |

## 怎么跑

```bash
cd java && ./gradlew bootRun
# RAG 用例需先 ingest
curl -s -X POST http://localhost:8080/ai-example/rag/ingest

curl -s -X POST http://localhost:8080/ai-example/eval/run \
  -H 'Content-Type: application/json' \
  -d '{"provider":"deepseek"}' | jq '.passed, .failed, .cases'
```

前端侧栏 **评测** Tab 一键跑 suite。

Python 对照（读同一 JSON schema，跑 tools / agent 子集）：

```bash
cd python && uv run python -m ai_example.samples.eval_runner
```

## 学什么

- golden set 是回归基础
- 通过率、工具失败率、步数比主观感觉可靠
- Token 用量已在 v0.2.0 补丁接入，评测报告可汇总 `usageSummary`

## 刻意不做

离线看板、分布式跑批、LangSmith 式 tracing 产品 — 见 [backlog.md](../backlog.md)。
