# 工业级第七阶段：架构与人工验证

对照本机演示：**查询扩展默认关闭、异步入库、本地 KEK 轮换、Prometheus/Grafana 可观测、k6 摘要可见**。默认验证不调用 Chat LLM / Embedding，也不进入 CI。

这是工业级第七阶段，不是课程「第七期 7a 语义分块」，也不是第六阶段双实例对照。第六阶段仍见 [industrial-ha.md](industrial-ha.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)（Compose 下 `http://127.0.0.1:8088/industrial.html` ）。登录后打开左侧「阶段 7 验收」，可以逐项运行页面可安全完成的检查。

> 页面中的“刷新全部只读检查”只调用 ADMIN 专用的 `GET /api/v1/ops/diagnostics`，不会调用 LLM、提交入库、轮换 KEK、执行 k6 或直接抓取 Prometheus。Redis CLI、双实例重启、Prometheus targets 和宿主机文件挂载仍按本文命令人工确认。

## 1. 验收范围

### 1.1 API 域边界

| API 域 | 路径 | 用途 | 是否属于本文验收 |
| --- | --- | --- | --- |
| 教学样例 | `/ai-example/rag/**`、`/ai-example/chat/**` 等 | 课程演示；例如匿名、同步的样例入库 | 否 |
| 工业 API | `/ai-example/api/v1/**` | JWT、角色控制、生产问答、异步入库、运维摘要、密钥轮换 | 是 |
| Actuator | `/ai-example/actuator/**` | readiness、受独立 Bearer 保护的 Prometheus 指标 | 仅验 health 与 metrics |

> `POST /ai-example/rag/ingest` 是教学同步入口，不要用它验收 7b。本文的入库入口必须是 `POST /ai-example/api/v1/rag/ingest`。

### 1.2 要证明什么

| 切片 | 必做通过标准 | 有额外凭据时选做 |
| --- | --- | --- |
| 7a 查询扩展 | 工业页默认「无扩展」；默认配置为 `none`；不带 `queryExpansion` 的流式 URL 不额外触发扩展 LLM | 有 Chat + Embedding 时验证 rewrite / HyDE；sources 仍来自真实语料 |
| 7b 异步入库 | alice 为 403；ADMIN 得到 202 + `jobId`；任务进入 `succeeded` 或 `failed` 终态且错误可观测；正常消费只记录一个 `instanceId` | 有 Embedding 时要求 `succeeded` 且 `chunkCount > 0` |
| 7c 本地轮换 | 新 current + 旧 previous 时仍可登录；ADMIN 重加密；移除 previous 后仍可登录；alice 为 403 | 无 |
| 7d 可观测 | 匿名 scrape 不是 200；专用 metrics token 可抓取两实例；Prometheus targets 为 UP；Grafana 可看到实例时序 | 触发业务流量后观察指标变化 |
| 7e 压测摘要 | k6 生成本机 summary；Java 能看到文件时 `/ops/loadtest` 返回 reqs / p95 | Compose 模式挂载 summary 后验证工业页展示 |

**无 Embedding Key 时，7b 的 `failed + errorMessage` 只能证明异步管道和失败可观测，不能证明索引构建成功。**

## 2. 底层架构

### 2.1 部署拓扑

```text
浏览器 / curl
      │
      ▼
Nginx :8088
least_conn；SSE 不缓冲
      │
      ├───────────────┐
      ▼               ▼
 java-a              java-b
 容器 :8080          容器 :8080
 宿主机 :8080        宿主机 :8082
      │               │
      ├───────┬───────┤
      ▼       ▼       ▼
PostgreSQL  Redis   Micrometer
/ pgvector   │        │
             │        └── Prometheus :9090 ── Grafana :3000
             └── Stream、任务 Hash、SSE 事件、会话锁
```

- Nginx 对 `/ai-example/**` 使用 `least_conn` 转发到两台 Java，并关闭代理缓冲，避免 SSE 增量被攒成整块。响应头 `X-Instance-Id` 可观察请求落在哪台实例。
- PostgreSQL 保存 Flyway 表、生产会话、审计、`prod_secret` 密文和 pgvector 数据。
- Redis 保存工业链路的会话锁、SSE 事件日志以及异步入库队列和任务状态。
- 两台 Java 的 Micrometer 指标各自在进程内累加；Prometheus 每 10 秒抓取两台实例，Grafana 再查询 Prometheus。
- 工业页不直连 Prometheus。它通过 JWT 调 `/api/v1/ops/snapshot`，得到**当前命中实例**的安全摘要；该接口不是双实例全局聚合。
- `/api/v1/ops/loadtest` 不查询 Prometheus，而是读取 Java 文件系统中的 k6 summary JSON。

对应配置：[docker-compose.yml](../docker-compose.yml)、[docker-compose.dev.yml](../docker-compose.dev.yml)、[frontend/nginx.conf](../frontend/nginx.conf) 和 [application.yml](../java/src/main/resources/application.yml)。

### 2.2 7a 查询扩展调用链

```text
GET /api/v1/chat/stream?queryExpansion=...
  → ProductionChatController
  → ProductionChatService
  → ProductionRetrievalService
  → ProductionQueryExpander（仅 rewrite / hyde）
  → pgvector + 可选关键词检索 / RRF
  → SSE meta / sources / answer
```

- 未传 `queryExpansion` 时使用 `app.production.query-expansion.default`，默认是 `none`，不会为查询扩展额外调用 LLM。
- `rewrite` 先让 LLM 改写问题，再用改写结果检索。
- `hyde` 让 LLM 生成假想文档并用于向量检索；开启 fuse 时还会融合原问题的向量结果。关键词检索仍使用原问题。
- SSE `sources` 始终来自真正命中的语料片段，不会把 HyDE 假想文档冒充来源。
- Agent 的 `search_kb` 不接收本次 chat 请求的 `queryExpansion`，不能用 Agent 流程代替 7a 验收。

### 2.3 7b Redis Stream 状态机

```text
submit → queued → running → succeeded
                         └→ failed
```

| Redis 数据 | 用途 |
| --- | --- |
| Stream `prod:ingest:stream` | 投递任务，决定哪个 consumer 执行 |
| Group `prod-ingest` | 让 `java-a`、`java-b` 竞争消费新消息 |
| Hash `prod:ingest:job:<jobId>` | 保存状态、结果、错误、租户和 `instanceId`，默认保留 1 小时 |
| String `prod:ingest:latest` | 指向最近任务 |
| String `prod:ingest:lock` | 全局活动任务锁；正常窗口内重复提交复用同一未终态任务 |

真正执行时会清理生产 corpus、重新分块、写入 pgvector，并在启用混合检索时维护关键词索引。因此它是**有状态验证**，不是只读探测。

> **当前可靠性边界：** consumer 使用 `XREADGROUP` 读取新消息，完成后 ACK；当前没有 `XPENDING` + `XCLAIM` / `XAUTOCLAIM` 的 pending 回收逻辑。正常完整消费后可以观察到 `pending=0`，但如果进程在读取后、ACK 前崩溃，另一实例不会自动接管该 pending 消息。因此本文不宣称“故障后自动恢复”或“任意故障下严格只执行一次”。活动锁 TTL 为 10 分钟，也不是无限期互斥保证。

### 2.4 7c KEK 信封加密

```text
PRODUCTION_KEK ──────────────┐
                             ├→ 解密 / 加密 PostgreSQL prod_secret
PRODUCTION_KEK_PREVIOUS ─────┘   （仅当前 KEK 解不开时回退）

POST /api/v1/secrets/rotate
  → 先解密全部行
  → 使用当前 KEK + PRODUCTION_KEK_ID 重新加密
  → 在同一数据库事务内写回
```

- KEK 本身不写入 `prod_secret`；表里保存 ciphertext、nonce 和 `kek_id`。
- 首次启动时，`SecretBootstrap` 只补齐缺失的 JWT HMAC 和已配置的 Provider Key，不会在每次重启时覆盖已有密文。
- 生产装配给 `JdbcSecretDAOImpl` 注入 `TransactionTemplate`：先确保所有行都能解密，再事务写回；解密失败时不会开始写入，写入失败会回滚。
- 两台 Java 在轮换窗口必须使用相同的 current、previous 和 `kekId`。只有重加密成功并复验后，才能移除 previous。

### 2.5 7d/7e 可观测数据面

| 数据 | 来源 | 读取方式 | 边界 |
| --- | --- | --- | --- |
| 实例指标 | 每台 Java 的 Micrometer registry | Prometheus 使用独立 Bearer scrape | 两实例计数可以不同 |
| Grafana 图表 | Prometheus 时序 | Grafana datasource | 操作后至少等一个 10 秒 scrape 周期 |
| 工业页快照 | `/api/v1/ops/snapshot` | 用户 JWT | 仅当前命中实例，不是全局总数 |
| 压测摘要 | `latest-summary.json` | `/api/v1/ops/loadtest` 读文件 | 文件必须对 Java 进程可见 |

Compose 默认给 Java 设置 `PRODUCTION_METRICS_TOKEN=dev-metrics-token`，而 Prometheus 的 Bearer 写在 [deploy/prometheus/prometheus.yml](../deploy/prometheus/prometheus.yml) 中。两者是独立配置源：修改环境变量不会自动改写 Prometheus 配置。

### 2.6 验收页与诊断接口

「阶段 7 验收」页面把以下安全信号汇总为五张可点击卡片：查询扩展默认值、Redis 入库 group 聚合、密钥存储可用性、依赖与实例指标、k6 摘要。数据来自：

```text
GET /ai-example/api/v1/ops/diagnostics
Authorization: Bearer <ADMIN JWT>
```

接口只返回固定的脱敏字段：模式枚举、布尔状态、consumer/pending 数、最近任务摘要、当前实例指标和 reqs/p95。它不会返回 KEK、previous KEK、metrics token、Provider Key、数据库/Redis 地址、文件路径、consumer 名或原始异常。

页面能够自动检查的是**应用层和共享 Redis 的只读状态**，不是完整部署证明：

- 7a 默认配置和客户端 URL 契约可自动检查，但 rewrite/HyDE 仍需有模型凭据后选跑。
- 7b 队列状态可自动检查；真正重建需要在页面输入 `INGEST` 二次确认。
- 7c 只能检查密钥存储是否可用并调用重加密；修改 current/previous、重启两实例和最终登录复验仍需人工完成。
- 7d 页面不获取 scrape token，也不直接查询 Prometheus；双 targets 必须到 Prometheus/Grafana 确认。
- 7e 页面只读取已有 summary，不能启动 k6 或替容器创建 bind mount。

## 3. 验证约定与环境准备

以下命令默认从仓库根目录执行，并使用 Bash/Zsh。准备公共变量：

```bash
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.dev.yml)
JAVA_A=http://127.0.0.1:8080/ai-example
JAVA_B=http://127.0.0.1:8082/ai-example
ENTRY=http://127.0.0.1:8088/ai-example
```

### 前置条件

1. Docker Desktop、`curl`、`jq` 可用；7e 还需本机 k6 或 Docker。
2. 仓库根目录有 `.env`，其中 `PRODUCTION_KEK` 是 Base64 编码的 32 字节 AES-256 key。可用 `openssl rand -base64 32` 生成。
3. `PRODUCTION_EVENT_LOG=redis`。设成 `memory` 时，入库会退回进程内队列，不能验收双实例竞争消费。
4. `PRODUCTION_METRICS_TOKEN` 必须与 `deploy/prometheus/prometheus.yml` 中的 Bearer 一致。
5. `:8080`、`:8082`、`:8088`、`:9090`、`:3000` 未被占用。端口冲突时修改映射，不要终止无关进程。

先检查配置，不启动服务：

```bash
test -n "${PRODUCTION_KEK:-$(grep '^PRODUCTION_KEK=' .env 2>/dev/null | cut -d= -f2-)}" \
  || { printf '%s\n' '缺少 PRODUCTION_KEK'; exit 1; }
"${COMPOSE[@]}" config --quiet
```

**期望：** 命令退出码为 0。此步骤只读，不调用 LLM，也不写业务数据。

## 4. 启动完整双实例栈

```bash
"${COMPOSE[@]}" up -d --build --wait --remove-orphans
"${COMPOSE[@]}" ps

curl -fsS http://127.0.0.1:8088/healthz
curl -fsS "$JAVA_A/actuator/health/readiness" | jq -e '.status == "UP"'
curl -fsS "$JAVA_B/actuator/health/readiness" | jq -e '.status == "UP"'
```

**期望：**

- `postgres`、`redis`、`java-a`、`java-b`、`frontend` 健康；readiness 同时包含数据库和 Redis 状态。
- `prometheus`、`grafana` 已运行。它们没有 Compose healthcheck，因此 `up --wait` 不等于 targets 已抓取成功或 Grafana 页面已经可用，后文单独验证。

**状态影响：** 创建或更新容器，保留已有 PostgreSQL / Redis volume。

## 5. 获取验收身份

```bash
ADMIN_TOKEN=$(curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -r '.token')

ALICE_TOKEN=$(curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r '.token')

test -n "$ADMIN_TOKEN" && test "$ADMIN_TOKEN" != null
test -n "$ALICE_TOKEN" && test "$ALICE_TOKEN" != null
```

**期望：** 两次检查退出码均为 0。admin 用于入库与轮换，alice 用于验证权限拒绝。

确认 Nginx 确实代理到 Java：

```bash
curl -fsS -D - -o /dev/null "$ENTRY/api/v1/me" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | grep -i '^X-Instance-Id:'
```

**期望：** 响应头为 `java-a` 或 `java-b`。登录及 `/me` 可能写审计或增加实例指标，不应视为完全无状态。

## 6. 7a 查询扩展

### 6.1 必做：默认关闭，不需要 LLM

1. 打开 `http://127.0.0.1:8088/industrial.html` 并登录。
2. 进入「生产问答」，确认分段控件默认选中「无扩展」。
3. 在浏览器 Network 中检查 `/api/v1/chat/stream`：默认请求不应包含 `queryExpansion`。
4. 静态配置应为：

```bash
grep -A2 'query-expansion:' java/src/main/resources/application.yml
```

**期望：** 默认值是 `${PRODUCTION_QUERY_EXPANSION:none}`。这证明默认不会为查询扩展额外调用 LLM；不代表完整问答在无 Chat/Embedding 配置时一定成功。

### 6.2 选做：rewrite / HyDE，需要 Chat + Embedding 与可检索语料

```bash
curl -sS -N \
  "$JAVA_A/api/v1/chat/stream?question=一期学了什么&queryExpansion=hyde&topK=4" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**期望：**

- SSE 中出现 `meta`、`sources` 等事件；元数据中的 `queryExpansion` 为 `hyde`。
- `sources` 是索引中真实语料的片段，不是 HyDE 生成的整段假想文档。
- 不以回答措辞是否固定作为通过标准。

**失败解释：** 模型或检索报错时，先确认 Chat Key、Embedding Key、生产索引是否存在；不要用 Agent `search_kb` 或教学 `/rag/query` 替代本步骤。

**状态影响：** 不重建索引，但会产生模型调用、审计和实例指标。

## 7. 7b 异步入库

### 7.1 权限拒绝

```bash
curl -sS -i -X POST "$JAVA_A/api/v1/rag/ingest" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

**期望：** HTTP 403，JSON `code` 为 `ingest_forbidden`。若得到同步结果或匿名成功，说明误打了教学 `/rag/ingest`。

### 7.2 投递与有限轮询

```bash
JOB_JSON=$(curl -fsS -X POST "$JAVA_A/api/v1/rag/ingest" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
printf '%s\n' "$JOB_JSON" | jq .
JOB_ID=$(printf '%s' "$JOB_JSON" | jq -er '.jobId')

for attempt in $(seq 1 120); do
  JOB_JSON=$(curl -fsS "$JAVA_A/api/v1/rag/ingest/jobs/$JOB_ID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
  STATUS=$(printf '%s' "$JOB_JSON" | jq -r '.status')
  printf 'attempt=%s status=%s\n' "$attempt" "$STATUS"
  case "$STATUS" in
    succeeded|failed) break ;;
  esac
  sleep 1
done

printf '%s\n' "$JOB_JSON" | jq .
test "$STATUS" = succeeded || test "$STATUS" = failed
```

**期望：**

- POST 的实际 HTTP 状态为 202；body 有 `jobId`。任务很快时，初次响应可能已接近终态。
- 有 Embedding Key：`succeeded`、`chunkCount > 0`，`instanceId` 为 `java-a` 或 `java-b`。
- 无 Embedding Key：`failed` 且有 `errorMessage`；请求不会一直挂起。
- 120 秒仍未进入终态时最后一条 `test` 失败，按排障表检查消费者和 Redis。

如需严格查看 POST 状态码，可单独执行：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -X POST "$JAVA_A/api/v1/rag/ingest" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

活动任务尚未结束时再次提交，应复用同一个未终态任务，而不是并行创建另一轮重建。

### 7.3 正常消费证据

```bash
"${COMPOSE[@]}" exec redis redis-cli XINFO GROUPS prod:ingest:stream
"${COMPOSE[@]}" exec redis redis-cli XINFO CONSUMERS prod:ingest:stream prod-ingest

curl -fsS "$JAVA_A/api/v1/ops/snapshot" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '{ingestSubmitted,ingestSucceeded,ingestFailed,ingestStatus,ingestJobId,ingestChunkCount,ingestError}'
```

**期望：** group 为 `prod-ingest`；正常完整消费后 pending 为 0；任务最终只记录一个 `instanceId`。`XINFO` 是正常路径辅助证据，不证明 consumer 崩溃后的自动恢复。

**状态影响：** 写 Redis Stream、Hash 和锁；成功时会重建 pgvector/关键词索引；同时改变处理实例的本地指标。

## 8. 7c 本地 KEK 轮换

> 这是会重写持久化密文的操作。只在可恢复的本地演示库执行；先安全记录旧 KEK，验证完成前不要删除唯一能解密现有数据的 key。

### 8.1 进入轮换窗口

```bash
OLD_KEK='填入当前 PRODUCTION_KEK'
NEW_KEK=$(openssl rand -base64 32)
printf 'new KEK generated, length=%s\n' "${#NEW_KEK}"
```

将两台 Java 的配置同时更新为：

```dotenv
PRODUCTION_KEK=<NEW_KEK>
PRODUCTION_KEK_PREVIOUS=<OLD_KEK>
PRODUCTION_KEK_ID=v2
```

然后重新创建两台实例：

```bash
"${COMPOSE[@]}" up -d --wait --force-recreate java-a java-b
```

不要只在当前 shell 临时前置三个变量后就假设以后重启仍安全；轮换窗口的配置应明确保存到本机受控配置中，且两实例保持一致。

### 8.2 先证实 previous 回退，再重加密

重新获取 token；旧 token 不作为新 KEK 可用性的证据：

```bash
ADMIN_TOKEN=$(curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -er '.token')
ALICE_TOKEN=$(curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -er '.token')
```

**期望：** 仍能获取 token，说明当前 KEK 解不开旧密文时可回退 previous。

权限检查和重加密：

```bash
curl -sS -i -X POST "$JAVA_A/api/v1/secrets/rotate" \
  -H "Authorization: Bearer $ALICE_TOKEN"

curl -fsS -X POST "$JAVA_A/api/v1/secrets/rotate" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

**期望：** alice 为 403 / `forbidden`；admin 返回 `rewritten >= 1` 且 `kekId` 为 `v2`。生产实现会先解密全部行，再在同一事务中按当前 KEK 写回。

### 8.3 退出轮换窗口

仅在 rotate 成功后，保留新 `PRODUCTION_KEK` 与 `PRODUCTION_KEK_ID=v2`，删除或清空 `PRODUCTION_KEK_PREVIOUS`，再执行：

```bash
"${COMPOSE[@]}" up -d --wait --force-recreate java-a java-b

curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' \
  | jq -e '.token | type == "string" and length > 0'
```

**期望：** 仍可签发 token。若跳过 rotate 就移除 previous，登录通常会返回 503；立即恢复能够解密旧密文的 current/previous 组合。

**状态影响：** 重启双实例、重写 `prod_secret`，并改变后续部署必须持有的 KEK。验完不要把配置恢复成与数据库密文不匹配的旧状态。

## 9. 7d Prometheus / Grafana

### 9.1 验证 scrape 安全边界

```bash
curl -sS -o /dev/null -w '%{http_code}\n' "$JAVA_A/actuator/prometheus"
```

**期望：** KEK 有效时为 401；KEK 缺失/无效时可能为 503；绝不能为 200。

使用与 Prometheus 配置一致的专用 token：

```bash
METRICS_TOKEN=${PRODUCTION_METRICS_TOKEN:-dev-metrics-token}

curl -fsS -H "Authorization: Bearer $METRICS_TOKEN" \
  "$JAVA_A/actuator/prometheus" | grep -E '^prod_'
curl -fsS -H "Authorization: Bearer $METRICS_TOKEN" \
  "$JAVA_B/actuator/prometheus" | grep -E '^prod_'
```

**期望：** 两台实例均暴露 `prod_ingest_submitted_total`、`prod_rate_limited_total`、`prod_chat_runs_total` 等业务指标。数值可以不同，因为 registry 位于各自进程内。

### 9.2 验证 Prometheus 与 Grafana

```bash
curl -fsS http://127.0.0.1:9090/api/v1/targets \
  | jq -e '[.data.activeTargets[] | select(.labels.app == "ai-example") | .health]
           | length == 2 and all(. == "up")'
```

**期望：** 两个 Java target 都为 `up`。失败时先核对 `deploy/prometheus/prometheus.yml` 的静态 Bearer 是否与 Java 环境变量一致。

浏览器验证：

1. 打开 `http://127.0.0.1:3000`，默认账号/密码为 `admin` / `admin`。
2. 进入 `Industrial` 文件夹，打开「工业级 /api/v1」（UID `industrial-prod`）。
3. 投递一次 ingest 或执行一次工业请求，等待至少一个 10 秒 scrape 周期。
4. 确认看板有按 `instance` 区分的时序；不要要求两实例 counter 完全相等。

**状态影响：** scrape 和看板查询只读；为制造指标而执行的业务请求会改变审计与实例 counter。

## 10. 7e k6 压测摘要

摘要是本机文件，不进 Git / 默认 CI。`404 loadtest_summary_missing` 表示 Java 看不到可读 summary，不表示 Prometheus 或 k6 本身故障。

### 10.1 生成并检查宿主机 summary

在 Java 当前确实看不到 summary 的干净环境中，请求可能返回：

```bash
curl -sS -i "$JAVA_A/api/v1/ops/loadtest" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**期望：** HTTP 404，`code` 为 `loadtest_summary_missing`。如果已存在可读 summary，直接得到 200 也正常。

执行 smoke：

```bash
./loadtest/run.sh smoke

test -s loadtest/results/latest-summary.json
jq -e '.metrics.http_req_duration.values["p(95)"] != null
       and .metrics.http_reqs.values.count != null' \
  loadtest/results/latest-summary.json
```

**期望：** summary 文件存在，包含请求数和 p95。默认 smoke 是本机冒烟结果，不是云 SLA 或容量基线。

### 10.2 让 Java 读到 summary

**方案 A：本机 Java。** 在 `java/` 目录运行 `./gradlew bootRun` 时，Controller 会探测 `../loadtest/results/latest-summary.json`。确保 8080 未被 Compose 的 `java-a` 占用。

**方案 B：Compose Java。** 宿主机 summary 默认不在 Java 容器中。创建临时 override，同时给两实例只读挂载并设置容器内路径：

```bash
cat > /tmp/ai-example-loadtest.yml <<EOF
services:
  java-a:
    environment:
      PRODUCTION_LOADTEST_SUMMARY: /loadtest-results/latest-summary.json
    volumes:
      - ${PWD}/loadtest/results:/loadtest-results:ro
  java-b:
    environment:
      PRODUCTION_LOADTEST_SUMMARY: /loadtest-results/latest-summary.json
    volumes:
      - ${PWD}/loadtest/results:/loadtest-results:ro
EOF

docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f /tmp/ai-example-loadtest.yml \
  up -d --wait --force-recreate java-a java-b frontend
```

然后重新获取 token 并验证：

```bash
ADMIN_TOKEN=$(curl -fsS "$JAVA_A/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -er '.token')

curl -fsS "$JAVA_A/api/v1/ops/loadtest" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  | jq '.metrics | {requests: .http_reqs.values.count, p95: .http_req_duration.values["p(95)"]}'
```

**期望：** HTTP 200，包含 requests 与 p95；工业页「可观测」中的最近压测显示 `N reqs · p95 Xms`。

**状态影响：** 写宿主机 `loadtest/results/latest-summary.json`；smoke 会产生登录、审计、权限拒绝和 metrics 流量。临时 override 仅改变容器挂载，不复制文件进镜像。

完整场景和阈值见 [loadtest/README.md](../loadtest/README.md)。

## 11. 自动化测试

以下测试验证局部契约，不替代真实 Redis 双实例、KEK 滚动窗口、Prometheus/Grafana 或容器文件挂载：

```bash
cd java
./gradlew test \
  --tests RagQueryExpanderTest \
  --tests ProductionRetrievalServiceImplTest \
  --tests InMemoryProductionIngestJobServiceImplTest \
  --tests JdbcSecretDAOImplTest \
  --tests ProductionJwtMvcTest \
  --tests ProductionSseMvcTest \
  --tests ProductionConfigurationTest

cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
```

| 测试层 | 覆盖 | 不覆盖 |
| --- | --- | --- |
| Java 定点单测 | 查询扩展、检索、内存任务状态机、KEK fallback/事务、JWT、SSE、条件装配 | 真实 Redis Stream 双实例和外部服务 |
| 前端类型检查/单测 | 默认 URL、HyDE 参数、工业 API 与面板行为 | Compose 网络和真实 Provider |
| 默认 Playwright | 不调用 Chat LLM 的页面主路径 | 真入库与真实检索 |
| `pnpm test:e2e:keys` | 有 key 环境下的真入库/检索 | KEK 轮换、Grafana、k6 |

## 12. 失败排查

| 现象 | 先查 |
| --- | --- |
| 登录 503 / JSON 无 token | `.env` 的 `PRODUCTION_KEK`；轮换时是否漏配 previous；两实例配置是否一致 |
| readiness 失败 | PostgreSQL、Redis 是否 healthy；`docker compose logs java-a java-b` |
| ingest 一直 queued/running | Redis 是否共用；`PRODUCTION_EVENT_LOG=redis`；consumer 日志；`XINFO GROUPS/CONSUMERS`；是否有遗留 pending |
| ingest 202 后很快 failed | 无 Embedding Key 时可预期；查看任务 `errorMessage` |
| alice ingest 不是 403 | 是否误打教学 `/rag/ingest`；token 是否实际属于 admin |
| 匿名 Prometheus 为 200 | `JwtAuthFilter` 的 metrics token 边界可能被破坏，不要通过匿名放行修复 |
| Prometheus target down | Java metrics token 与 `prometheus.yml` 静态 Bearer 是否一致 |
| Grafana 没数据 | targets 是否 UP；是否已等待 10 秒；查询是否按正确 `instance` 标签 |
| 工业页没有 p95 | summary 是否存在；Java 进程是否能看到同一路径；Compose 是否应用挂载 override |
| `bind: address already in use` | 修改对应 `*_PORT` 映射，不要终止无关本地进程 |

## 13. 清理

默认只停止容器，保留 PostgreSQL / Redis 数据：

```bash
"${COMPOSE[@]}" down --remove-orphans
rm -f /tmp/ai-example-loadtest.yml
```

如使用了 7e 临时 override，停止时也可带上同一个 `-f /tmp/ai-example-loadtest.yml`，再删除该文件。

> 谨慎使用 `down -v`：它会删除 PostgreSQL 与 Redis named volumes，包括 `prod_secret` 密文、索引和队列状态。KEK 轮换后应保留与现有数据库密文匹配的新 KEK；不要把配置随手恢复为旧 key。

`loadtest/results/latest-summary.json` 是本机生成物，可按需保留或删除。

## 14. 刻意不做

- 云 KMS / Vault / 定时自动轮换；把 KEK 写入 `prod_secret`
- 匿名刮取 actuator；云托管 Grafana
- 教学 `/rag/ingest` 改异步；对象存储 / 知识库后台
- Redis Stream pending 自动认领与 consumer 崩溃恢复
- `memory_rewrite`、父子分块、compare 对照课进入 `/api/v1`
- JMeter、把 k6 / HA / Grafana 纳入默认 CI
- 默认测试套件调用 Chat LLM
