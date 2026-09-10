# 工业级第七阶段：人工验证

对照本机演示：**查询扩展可关、异步入库、本地 KEK 轮换、Grafana 刮 Prometheus、压测摘要可见**。默认不打 Chat LLM / Embedding，不进 CI。

这是工业级第七阶段，不是课程「第七期 7a 语义分块」，也不是第六阶段双实例对照。第六阶段仍见 [industrial-ha.md](industrial-ha.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)（Compose 下 `http://127.0.0.1:8088/industrial.html`）。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 7a 默认关 | 工业页查询扩展默认「无扩展」；不带 `queryExpansion` 的流式 URL 与现在一致。打开 rewrite/HyDE **要 LLM**，本机选跑 |
| 7b 异步入库 | ADMIN `POST /rag/ingest` → **202** + `jobId`；alice **403**；轮询 job 能看到 `queued`/`running`/`succeeded`/`failed`。双实例同一 job 只有一个 `instanceId` |
| 7c 本地轮换 | 同时持有 `PRODUCTION_KEK` + `PRODUCTION_KEK_PREVIOUS` 时能登录；ADMIN `POST /secrets/rotate` 重加密；去掉 previous 后仍能登录。alice **403** |
| 7d Grafana | 匿名打 `/actuator/prometheus` **不是 200**；带 `PRODUCTION_METRICS_TOKEN` 能刮到 `prod_` 指标。Grafana `:3000` 能看到两台 Java |
| 7e 压测摘要 | 没跑过 k6 时 `GET /ops/loadtest` **404**；`./loadtest/run.sh smoke` 后工业页「最近压测」有 reqs / p95 |

**为何入库成功标「本机选跑」：** 真建索引要 Embedding。没配 Key 时任务仍会投递，状态会落到 `failed` 并带错误文案——这已经证明管道可观测。

## 前置

1. 仓库根目录有 `.env`，且 **`PRODUCTION_KEK` 已设**（`openssl rand -base64 32`）。缺 KEK 时进程能起，`/api/v1` 是 503。
2. Docker Desktop 可用。Grafana / Prometheus / 双实例入库都依赖 Compose。
3. 本机有 `curl`、`jq`。7e 还要本机 `k6` 或 Docker（见 [loadtest/README.md](../loadtest/README.md)）。
4. **不要用 Vite 验证 Grafana / 双实例。** 整栈入口是 Nginx `:8088`。curl 对照可直打 `java-a` `:8080`、`java-b` `:8082`（需 `docker-compose.dev.yml`）。
5. Compose 默认 `PRODUCTION_METRICS_TOKEN=dev-metrics-token`，须与 [deploy/prometheus/prometheus.yml](../deploy/prometheus/prometheus.yml) 里的 Bearer **一致**。改过 token 两边都要改。

会话锁 / 事件日志 / 入库队列默认都走 Redis。`PRODUCTION_EVENT_LOG=memory` 时入库会退回进程内队列，双实例「只消费一次」不再成立。

## 端口

| 谁 | 宿主机默认 | 说明 |
| --- | --- | --- |
| java-a | `JAVA_A_PORT` **8080** | 需 `docker-compose.dev.yml` |
| java-b | `JAVA_B_PORT` **8082** | 同上 |
| frontend（Nginx） | `FRONTEND_PORT` **8088** | 工业页 |
| Prometheus | `PROMETHEUS_PORT` **9090** | 浏览器可开，工业页不直连 |
| Grafana | `GRAFANA_PORT` **3000** | 默认 `admin` / `admin` |
| Jaeger | **16686** | 第三阶段已有 |

本机 `./gradlew bootRun` 也占 8080。冲突时改映射，不要杀别人的进程。

## 1. 起栈

在仓库根目录：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait --remove-orphans
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

期望：`java-a`、`java-b`、`frontend`、`postgres`、`redis`、`prometheus`、`grafana` 均为 Up（Java / frontend 为 healthy）。

换令牌（后面步骤复用）：

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -r .token)
ALICE=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)
```

`TOKEN` / `ALICE` 应是 JWT，不是 `null`。

## 2. 7b 异步入库（无 Key 也可验投递）

alice 禁止：

```bash
curl -sS -D - -o /tmp/ingest-alice.json \
  -X POST http://127.0.0.1:8080/ai-example/api/v1/rag/ingest \
  -H "Authorization: Bearer $ALICE"
cat /tmp/ingest-alice.json
```

期望：**403**，`code` 为 `ingest_forbidden`。

admin 投递：

```bash
curl -sS -D - -o /tmp/ingest-job.json \
  -X POST http://127.0.0.1:8080/ai-example/api/v1/rag/ingest \
  -H "Authorization: Bearer $TOKEN"
cat /tmp/ingest-job.json
```

期望：**202**，body 有 `jobId`，`status` 为 `queued` 或 `running`（消费很快时也可能已经 `succeeded` / `failed`）。

轮询：

```bash
JOB_ID=$(jq -r .jobId /tmp/ingest-job.json)
curl -sS "http://127.0.0.1:8080/ai-example/api/v1/rag/ingest/jobs/${JOB_ID}" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

期望：`status` 进入终态。配了 Embedding 时 `succeeded` 且 `chunkCount` > 0，`instanceId` 为 `java-a` **或** `java-b` 之一。没配 Key 时 `failed` + `errorMessage`，**不是** HTTP 一直挂起。

可观测快照应带上最近任务：

```bash
curl -sS http://127.0.0.1:8080/ai-example/api/v1/ops/snapshot \
  -H "Authorization: Bearer $TOKEN" | jq '{ingestSubmitted,ingestSucceeded,ingestStatus,ingestJobId}'
```

工业页：admin 登录 → 生产问答 →「重建索引」，会轮询直到终态。alice 同一按钮应失败（403）。

双实例只消费一次：同一 `JOB_ID` 再查，`instanceId` 不会在两次查询之间从 a 跳到 b。Redis：

```bash
docker compose exec redis redis-cli XINFO GROUPS prod:ingest:stream
```

期望：组名 `prod-ingest`，pending 最终回到 0。

教学样例 `POST /ai-example/rag/ingest` **仍同步、匿名**，不要拿它当工业验收。

## 3. 7c 本地 KEK 轮换

不要在验证中途把 KEK 从 `.env` 删掉就重启，否则 `/api/v1` 全 503。

记下当前 `.env` 里的 `PRODUCTION_KEK` 为 **旧钥匙**，再生成新钥匙：

```bash
OLD_KEK='（当前 PRODUCTION_KEK）'
NEW_KEK=$(openssl rand -base64 32)
```

滚动窗口（两台都要带 previous，否则有一台解不开旧密文）：

```bash
PRODUCTION_KEK="$NEW_KEK" \
PRODUCTION_KEK_PREVIOUS="$OLD_KEK" \
PRODUCTION_KEK_ID=v2 \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --wait java-a java-b
```

若 Compose 主要从 `.env` 读变量，把这三项写进 `.env` 再 `up` 更稳。

此时应仍能登录（先试 previous 解旧密文）：

```bash
curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"demo"}' | jq -r .token
```

alice 不能转：

```bash
curl -sS -D - -o /tmp/rotate-alice.json \
  -X POST http://127.0.0.1:8080/ai-example/api/v1/secrets/rotate \
  -H "Authorization: Bearer $ALICE"
```

期望：**403** `forbidden`。

admin 重加密：

```bash
curl -sS -X POST http://127.0.0.1:8080/ai-example/api/v1/secrets/rotate \
  -H "Authorization: Bearer $TOKEN" | jq .
```

期望：`rewritten` ≥ 1，`kekId` 为 `v2`。工业页安全面板「重加密密钥（本地 KEK）」同一效果。

去掉 previous 后再起一次：

```bash
# .env：PRODUCTION_KEK=$NEW_KEK，PRODUCTION_KEK_ID=v2，删掉或清空 PRODUCTION_KEK_PREVIOUS
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --wait java-a java-b
```

期望：仍能换 token、打开工业页。若跳过 rotate 就去掉 previous，登录会 503。

验完建议把 `.env` 的 KEK 固定下来，避免下次起栈用另一把钥匙解不开库里的密文。

## 4. 7d Grafana / Prometheus

匿名刮取必须失败（不要改成公网匿名）：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8080/ai-example/actuator/prometheus
```

期望：**401**（有 KEK）或 **503**（没 KEK）。不是 200。

带 scrape token：

```bash
curl -sS -H 'Authorization: Bearer dev-metrics-token' \
  http://127.0.0.1:8080/ai-example/actuator/prometheus | grep prod_
```

对 `:8082` 再打一遍，两边都应有 `prod_ingest_submitted` / `prod_rate_limited` 一类指标。

浏览器：

1. Prometheus `http://localhost:9090/targets`：`java-a:8080`、`java-b:8080` 为 UP。
2. Grafana `http://localhost:3000`（`admin` / `admin`）→ 文件夹 Industrial → 看板「工业级 /api/v1」（uid `industrial-prod`）。投一次 ingest 或刷新工业页可观测后，过约 10s 应有线。
3. 工业页「可观测」有「打开 Grafana」链接；**不要**让浏览器直连 Prometheus 当产品能力。

## 5. 7e 压测摘要

摘要文件只对**这台机器**有意义，不进 git / CI。

先确认没有摘要时的 404：

```bash
curl -sS -D - -o /tmp/loadtest.json \
  http://127.0.0.1:8080/ai-example/api/v1/ops/loadtest \
  -H "Authorization: Bearer $TOKEN"
```

期望：**404** `loadtest_summary_missing`。工业页文案含「尚未跑 `./loadtest/run.sh smoke`」。

压测仍直打 Java `:8080`（不要打 Vite、也不要打 `:8088` 当容量基线）：

```bash
./loadtest/run.sh smoke
```

期望：写出 `loadtest/results/latest-summary.json`。再请求 `/ops/loadtest` 为 **200**，含 `metrics.http_req_duration`。刷新工业页「可观测」，`data-testid=loadtest-summary` 出现 reqs / p95。

Java 若在容器里跑、k6 在宿主机跑：摘要写在**宿主机**仓库目录。`GET /ops/loadtest` 读的是 **Java 进程的工作目录**；Compose 镜像里通常没有这份文件，面板会继续 404。两种验法：

- 本机 `cd java && ./gradlew bootRun`（cwd 能落到 `../loadtest/results/latest-summary.json`），或
- `PRODUCTION_LOADTEST_SUMMARY=/绝对路径/loadtest/results/latest-summary.json` 挂进容器后再刷面板。

数字随本机抖动，不要当云 SLA。完整场景见 [loadtest/README.md](../loadtest/README.md)。

## 6. 7a 查询扩展（默认关；打开要 LLM）

工业页生产问答：分段控件「无扩展 / 改写 / HyDE」，默认 **无扩展**。不选时流式请求 **不应**带 `queryExpansion`。

本机选跑（已配 Chat + Embedding Key）：

```bash
curl -sS -N \
  'http://127.0.0.1:8080/ai-example/api/v1/chat/stream?question=一期学了什么&queryExpansion=hyde&topK=4' \
  -H "Authorization: Bearer $TOKEN"
```

期望：`event:meta`（或 sources）里 `queryExpansion` 为 `hyde`；`event:sources` 的文本是语料原文，**不是**整段假想文档。默认不带该参数时行为与第六阶段相同。

Agent `search_kb` **不走** 请求级 expansion（仍 `none`）。教学 `/rag/query` 的 `compare-*` / `memory_rewrite` 不是本阶段验收。

## 7. 可选：不启容器的单测

不替代上面的 Compose / 页面步骤：

```bash
cd java
./gradlew test --tests RagQueryExpanderTest --tests ProductionRetrievalServiceImplTest \
  --tests InMemoryProductionIngestJobServiceImplTest --tests JdbcSecretDAOImplTest \
  --tests ProductionJwtMvcTest --tests ProductionSseMvcTest --tests ProductionConfigurationTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
```

Playwright 默认套件范围不变（仍不打 Chat LLM）。空检索 / 真入库仍是 `pnpm test:e2e:keys`。

## 失败时

| 现象 | 先查 |
| --- | --- |
| 登录 503 / JSON 无 token | `.env` 的 `PRODUCTION_KEK`；刚轮换是否漏了 previous 或没 rotate |
| ingest 一直 queued | Redis 是否两台共用；`PRODUCTION_EVENT_LOG` 不要改成把入库消费者卸掉；看 `docker compose logs java-a java-b` |
| ingest 202 但很快 failed | 没配 Embedding 是预期；看 `errorMessage` |
| alice ingest 不是 403 | 打的是教学 `/rag/ingest`，或 token 其实是 admin |
| 匿名 prometheus 200 | 不要放行 actuator；确认 `JwtAuthFilter` 仍校验 scrape token |
| Grafana 没数据 | Prometheus targets 是否 UP；token 是否与 `prometheus.yml` 一致；看板 uid `prometheus` 数据源 |
| 工业页没有 p95 | 摘要是否写在 Java 能读到的路径；见上文 7e |
| `bind: address already in use` 3000 / 9090 | 本机已有 Grafana/Prometheus；改 `GRAFANA_PORT` / `PROMETHEUS_PORT` |

## 刻意不做

- 云 KMS / Vault / 定时自动轮换；把 KEK 写入 `prod_secret`
- 匿名刮取 actuator；云托管 Grafana
- 教学 `/rag/ingest` 改异步；对象存储 / 知识库后台
- `memory_rewrite`、父子分块、compare 对照课进 `/api/v1`
- JMeter、把 k6 / HA / Grafana 打进 CI
- 默认套件打 Chat LLM
