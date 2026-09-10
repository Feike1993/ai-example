# 工业级第五阶段：本机 k6 压测

打的是 Java **`/ai-example/api/v1`**（默认 `http://127.0.0.1:8080`），不是 Vite、也不是 Playwright。默认套件 **不打 Chat LLM / Embedding**。不进 CI（结果随本机抖动；令牌桶单测见 `RedisTokenBucketIT`）。

这是工业级第五阶段，与课程文档里的「第四期 Hybrid RAG」不是同一件事；也不是第四阶段浏览器 E2E。

## 前置

1. PostgreSQL + Redis 已起（`docker compose up -d` 或 `docker-compose.dev.yml`）
2. 已设 `PRODUCTION_KEK`（`openssl rand -base64 32`）
3. Java 在 **8080**：`cd java && ./gradlew bootRun`
4. 本机有 `k6`（`brew install k6`）或 Docker Desktop

## 怎么跑

```bash
./loadtest/run.sh smoke        # 廉价读 + alice ingest 403
./loadtest/run.sh guardrail    # POST /chat 违禁词 → 422 input_deny
./loadtest/run.sh rate_limit   # 打穿 chat 桶 → 429 rate_limited
./loadtest/run.sh login_limit  # 打穿登录 IP 桶 → 429
./loadtest/run.sh all
```

`run.sh` 优先本机 `k6`；否则 `docker run grafana/k6:1.0.0`。Mac 上容器访问宿主机用 `host.docker.internal`。覆盖地址：

```bash
BASE_URL=http://127.0.0.1:8080/ai-example ./loadtest/run.sh smoke
```

不要用 `vite preview`（无 `/ai-example` 代理）。Compose 前端是 8088 的 Nginx，压测仍应直打 **8080** 的 Java。

## 场景

| 脚本 | 请求 | 期望 | 默认 VU |
| --- | --- | --- | --- |
| `smoke.js` | `GET /me` `/audit` `/ops/snapshot`；alice `POST /rag/ingest` | 200 / **403** `ingest_forbidden` | 8 VU × 30s |
| `guardrail.js` | `POST /chat` 问句含 `违禁演示词` | **422** `input_deny` | 1 VU × 5 次 |
| `rate_limit.js` | 同上护栏题连打 | 出现 **429** + `Retry-After` | 1 VU × 40 次 |
| `login_limit.js` | `POST /auth/token` 同 IP 连打 | 出现 **429** | 1 VU × 15 次 |

登录只在 `setup()` 做一次，VU 复用 JWT，避免把登录桶打光。422 / 429 / 403 用 k6 `expectedStatuses`，不算 `http_req_failed`。

**刻意不做**：`session_busy` 409。护栏在会话锁之前返回，要占住锁必须走过检索/生成（Chat LLM），不进默认套件。

## 限流数字（默认）

| 桶 | 配置 | 默认 |
| --- | --- | --- |
| chat / agent | `PRODUCTION_RATE_CAPACITY` / `WINDOW` | **30 / 60s** / 租户+主体 |
| 登录 | `PRODUCTION_LOGIN_CAPACITY` | **10 / 60s** / IP |

`GET /me`、`/audit`、`/ops/snapshot`、`/sessions/*`、`POST /rag/ingest` **不走** chat 令牌桶，适合做廉价容量基线。

跑完可登录工业级页看可观测面板，或：

```bash
TOKEN=$(curl -s http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)
curl -s http://127.0.0.1:8080/ai-example/api/v1/ops/snapshot \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## 如何记录一次本机容量基线

只测廉价读（`smoke`），数字只对你这台机器有意义，不要当云上 SLA。

1. Java / Redis / Postgres 已热身。
2. `./loadtest/run.sh smoke`，脚本会把 k6 `--summary-export` 写到 `loadtest/results/latest-summary.json`（gitignore）。工业页可观测面板会读 `GET /api/v1/ops/loadtest`。第七阶段整页验证（含 Compose 里 Java 读不到宿主机摘要时怎么挂路径）见 [docs/industrial-ops.md](../docs/industrial-ops.md)。
3. 需要更高到达率时改 `smoke.js` 的 `vus` / `duration`，或临时 `PRODUCTION_RATE_CAPACITY=1000` 后**重启 Java**（这只影响 chat/agent，不影响 smoke）。

默认 **30/min** 是 chat 限流教学数字，不是「本机能打多少次 LLM」。真实 Chat 吞吐本阶段不做。
