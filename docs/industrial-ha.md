# 工业级第六阶段：多实例验证

对照 **两份 Java + 一份 Redis**：会话锁跨进程互斥、SSE 事件跨实例回放。不打 Chat LLM / Embedding，不进 CI。

这是工业级第六阶段，不是课程「第十四期」，也不是第五阶段 k6、第四阶段 Playwright。

一键脚本：[`scripts/industrial-ha.sh`](../scripts/industrial-ha.sh)。下文把脚本在做什么、以及脚本之外怎么用手点一遍都写清，方便隔几个月再验。

## 要证明什么

| 项 | 通过标准 |
| --- | --- |
| 双进程 | Compose 同时有 `java-a`、`java-b`，共用镜像 `ai-example-java` 和 Redis |
| 身份可辨 | `/api/v1/**` 响应头 `X-Instance-Id` 为 `java-a` 或 `java-b` |
| 会话锁跨实例 | 同一 `sessionId`：A 持锁时打 B → **409** `session_busy`，且两台 `X-Instance-Id` 不同 |
| SSE 跨实例续传 | A 写 `GET /api/v1/sse-probe`，B 用 `GET /api/v1/runs/{runId}/stream` + `Last-Event-ID: -1` 回放到含 `ha-probe` 的事件 |
| Nginx | `:8088` 把 `/ai-example/` 打到两台上（`least_conn`），不要绑死一台 |

**为何用探针、不走真实 Chat：** 输入护栏在 `tryAcquire` 之前返回，默认套件里打不到 `session_busy`。真实占锁必须走过检索/生成，会绑 LLM。`lock-probe` / `sse-probe` 只占 Redis 锁、写假 SSE。

## 前置

1. 仓库根目录有 `.env`，且 **`PRODUCTION_KEK` 已设**（`openssl rand -base64 32`）。缺 KEK 时进程能起，但 `/api/v1` 是 503。
2. Docker Desktop 可用。
3. 本机有 `curl`、`python3`（脚本依赖）。
4. **不要用 Vite**（`pnpm dev` / `vite preview` 没有双实例反代）。整栈入口是 Nginx `:8088`；对照脚本**直打**两台 Java，不经过 Nginx。
5. 旧栈若还留着单服务 `java`，起新栈时加 `--remove-orphans`。

会话锁默认 Redis（`PRODUCTION_SESSION_LOCK=redis`），事件日志默认 Redis（`PRODUCTION_EVENT_LOG=redis`）。改成 `memory` 则跨实例演示无意义。

## 端口

| 谁 | 容器内 | 宿主机（`docker-compose.dev.yml`） |
| --- | --- | --- |
| java-a | 8080 | `JAVA_A_PORT`，默认 **8080** |
| java-b | 8080 | `JAVA_B_PORT`，默认 **8082** |
| frontend（Nginx） | 80 | `FRONTEND_PORT`，默认 **8088** |

本机 `./gradlew bootRun` 也占 8080。已被占用时不要杀别人的进程，改映射即可：

```bash
JAVA_A_PORT=8083 docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --wait java-a
JAVA_A_URL=http://127.0.0.1:8083/ai-example ./scripts/industrial-ha.sh
```

脚本默认 `JAVA_A_URL=http://127.0.0.1:8080/ai-example`、`JAVA_B_URL=http://127.0.0.1:8082/ai-example`。

## 1. 起双实例栈

在仓库根目录：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait --remove-orphans
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

期望：`java-a`、`java-b`、`frontend`、`postgres`、`redis` 均为 healthy（或 frontend 已 Up）。日志：

```bash
docker compose logs -f java-a java-b frontend
```

就绪探测：

```bash
curl -fsS http://127.0.0.1:8088/healthz
curl -fsS http://127.0.0.1:8088/ai-example/actuator/health/readiness
curl -fsS -o /dev/null -D - http://127.0.0.1:8080/ai-example/actuator/health/readiness
curl -fsS -o /dev/null -D - http://127.0.0.1:8082/ai-example/actuator/health/readiness
```

`actuator/health` **不在** `/api/v1` 下，**没有** `X-Instance-Id`。看实例头请打下一节的 `/api/v1/me`。

## 2. 一键对照（推荐）

```bash
./scripts/industrial-ha.sh
```

端口改过时带 URL：

```bash
JAVA_A_URL=http://127.0.0.1:8083/ai-example \
JAVA_B_URL=http://127.0.0.1:8082/ai-example \
./scripts/industrial-ha.sh
```

登录账号默认 `alice` / `demo`（可用 `USERNAME` / `PASSWORD` 覆盖）。JWT 存在 Postgres 信封里，两台共用同一把 HMAC，A 换的令牌 B 认。

**通过时末尾类似：**

```text
锁探针通过：两台实例不同，第二枪 409 session_busy
SSE 续传通过：写入 java-a runId=…，回放 java-b
全部通过。
```

脚本步骤：登录 A → 用 `/me` 预热 B → A 持锁 4s 同时打 B → A 写 sse-probe → B 按 `Last-Event-ID: -1` 回放。

## 3. 手工逐步（脚本忘了或要看原始报文）

下面命令假设 A=`8080`、B=`8082`。先拿令牌：

```bash
TOKEN=$(curl -sf http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
echo "$TOKEN"
```

### 3.1 直连两台，看实例头

```bash
curl -sS -D - -o /dev/null http://127.0.0.1:8080/ai-example/api/v1/me \
  -H "Authorization: Bearer $TOKEN" | grep -i x-instance-id
# 期望：java-a

curl -sS -D - -o /dev/null http://127.0.0.1:8082/ai-example/api/v1/me \
  -H "Authorization: Bearer $TOKEN" | grep -i x-instance-id
# 期望：java-b
```

未带令牌时仍是 401，但过滤器先写头，`X-Instance-Id` 仍在。

### 3.2 经 Nginx 看负载

`least_conn` 不是严格轮询，连打若干次应能看到两种实例名（不一定 1:1）：

```bash
for i in 1 2 3 4 5 6 7 8; do
  curl -sS -D - -o /dev/null http://127.0.0.1:8088/ai-example/api/v1/me \
    | awk 'BEGIN{IGNORECASE=1} /^HTTP\// || /^x-instance-id:/'
  echo
done
```

只出现一个名字：查 `docker compose ps` 是否两台都 healthy，以及 `frontend/nginx.conf` 的 `upstream industrial_java` 是否同时写了 `java-a:8080` 与 `java-b:8080`。

### 3.3 会话锁：第二枪 409

同一 `sessionId`，A 持锁期间打 B：

```bash
SID="ha-manual-$(date +%s)"

curl -sS -D - \
  -X POST "http://127.0.0.1:8080/ai-example/api/v1/sessions/${SID}/lock-probe" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"holdMs":4000}' &

sleep 0.5

curl -sS -D - \
  -X POST "http://127.0.0.1:8082/ai-example/api/v1/sessions/${SID}/lock-probe" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"holdMs":1}'
```

| 请求 | 期望 |
| --- | --- |
| A | 200，`{"sessionId":"…","instanceId":"java-a","heldMs":4000}`，头 `X-Instance-Id: java-a` |
| B | **409**，`{"code":"session_busy","message":"…"}`，头 `X-Instance-Id: java-b` |

`holdMs` 缺省 3000，上限 10000。A 返回后锁已释放，再打 B 应为 200。

409 是会话冲突，不是限流；限流是 **429** + `Retry-After`。不要把两件事混在一起。

### 3.4 SSE：A 写、B 回放

```bash
curl -sS -N -D - \
  "http://127.0.0.1:8080/ai-example/api/v1/sse-probe" \
  -H "Authorization: Bearer $TOKEN"
```

记下 `meta` 里的 `runId`，body 里应有 `ha-probe`。然后在 **另一台** 回放（从 seq -1 起，等于从头）：

```bash
curl -sS -N -D - \
  "http://127.0.0.1:8082/ai-example/api/v1/runs/<runId>/stream" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Last-Event-ID: -1"
```

期望：头是 `java-b`，body 仍含 `ha-probe` 与 `event:done`。run 过期是 **410** `run_gone`（默认窗口 `PRODUCTION_REPLAY_TTL`，约 10 分钟）。

## 4. 可选：不启容器的单测

不替代 Compose 对照，只确认探针与 MVC 契约：

```bash
cd java
./gradlew test --tests ProductionChatServiceImplTest --tests ProductionJwtMvcTest --tests ProductionSseMvcTest
```

## 失败时

| 现象 | 先查 |
| --- | --- |
| 登录 / 脚本 `curl: (22)` 或 JSON 无 `token` | `.env` 里 `PRODUCTION_KEK`；`curl` 直打 8080 是否 503 |
| `bind: address already in use` 8080 | 本机 `bootRun` 或旧容器；改 `JAVA_A_PORT` 并同步 `JAVA_A_URL` |
| 两枪都是 200 | B 太慢、A 已放锁；先 `GET /me` 预热 B，或把 `holdMs` 加大；确认两台都连**同一** Redis |
| 409 但 `X-Instance-Id` 相同 | 两枪打到了同一进程（URL 写错，或没走 compose.dev 的 8082） |
| SSE 回放没有 `ha-probe` / 410 | `PRODUCTION_EVENT_LOG` 是否 `redis`；runId 是否抄错；是否超过 replay TTL |
| Nginx 只有一个实例名 | 一台 Java 未 healthy；frontend 镜像过旧（改过 `nginx.conf` 需 `--build`） |
| 前端镜像 `tsc` 失败 | `frontend` 的 `pnpm build` 会跑 `tsc -b`，测试文件类型错误会拦住整栈 |

Java 日志：`docker compose logs java-a java-b`。Redis：`docker compose exec redis redis-cli ping`。

## 刻意不做

- 真实 Chat / Agent LLM、Embedding
- Grafana、云 KMS、JMeter
- 把本对照或压测塞进 CI
- 用 Vite 演示双实例
- 把 `session_busy` 绑在真实问答上（见文首「为何用探针」）
