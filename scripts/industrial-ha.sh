#!/usr/bin/env bash
# 工业级第六阶段：跨实例会话锁 + SSE 续传。不打 Chat LLM。
# 逐步说明：docs/industrial-ha.md
# 前置：docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
# java-a :8080、java-b :8082，且已设 PRODUCTION_KEK。
set -euo pipefail

A="${JAVA_A_URL:-http://127.0.0.1:8080/ai-example}"
B="${JAVA_B_URL:-http://127.0.0.1:8082/ai-example}"
USER_NAME="${USERNAME:-alice}"
PASSWORD="${PASSWORD:-demo}"
SID="ha-$(date +%s)"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "需要 $1" >&2
    exit 1
  }
}

need curl
need python3

login() {
  local url="$1"
  curl -sf "${url}/api/v1/auth/token" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${USER_NAME}\",\"password\":\"${PASSWORD}\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

header_instance() {
  python3 -c '
import sys
name = "x-instance-id"
for line in sys.stdin:
    if line.lower().startswith(name + ":"):
        print(line.split(":", 1)[1].strip())
        break
'
}

echo "=== 登录 java-a ==="
TOKEN="$(login "$A")"

echo "=== 预热 java-b（避免冷启动吃掉持锁窗口） ==="
curl -sf "${B}/api/v1/me" -H "Authorization: Bearer ${TOKEN}" >/dev/null

echo "=== 会话锁：java-a 持锁 4s，同时打 java-b 期望 409 ==="
curl -sS -D /tmp/ha-a.hdr -o /tmp/ha-a.json \
  -X POST "${A}/api/v1/sessions/${SID}/lock-probe" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"holdMs":4000}' &
A_PID=$!
sleep 0.4
B_CODE="$(curl -sS -o /tmp/ha-b.json -w '%{http_code}' -D /tmp/ha-b.hdr \
  -X POST "${B}/api/v1/sessions/${SID}/lock-probe" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  -d '{"holdMs":1}')"
wait "$A_PID"
A_INST="$(header_instance < /tmp/ha-a.hdr)"
B_INST="$(header_instance < /tmp/ha-b.hdr)"
echo "java-a X-Instance-Id=${A_INST} body=$(cat /tmp/ha-a.json)"
echo "java-b HTTP ${B_CODE} X-Instance-Id=${B_INST} body=$(cat /tmp/ha-b.json)"
python3 - <<PY
import json
code = "${B_CODE}"
body = json.load(open("/tmp/ha-b.json"))
assert code == "409", code
assert body.get("code") == "session_busy", body
a = "${A_INST}"
b = "${B_INST}"
assert a and b and a != b, (a, b)
print("锁探针通过：两台实例不同，第二枪 409 session_busy")
PY

echo "=== SSE：java-a 写探针流，java-b 按 Last-Event-ID 回放 ==="
export HA_TOKEN="$TOKEN"
export HA_A="$A"
export HA_B="$B"
python3 - <<'PY'
import json, os, urllib.request

token = os.environ["HA_TOKEN"]
a = os.environ["HA_A"]
b = os.environ["HA_B"]

def req(url, headers=None):
    h = {"Authorization": "Bearer " + token}
    if headers:
        h.update(headers)
    return urllib.request.Request(url, headers=h)

def read_sse(url, extra=None):
    with urllib.request.urlopen(req(url, extra), timeout=20) as resp:
        instance = resp.headers.get("X-Instance-Id")
        body = resp.read().decode()
    return instance, body

inst_a, body_a = read_sse(a + "/api/v1/sse-probe")
run_id = None
for line in body_a.splitlines():
    if line.startswith("data:"):
        raw = line[5:].strip()
        if not raw:
            continue
        payload = json.loads(raw)
        if isinstance(payload, dict) and payload.get("runId"):
            run_id = payload["runId"]
            break
assert run_id, body_a
assert "ha-probe" in body_a, body_a
inst_b, body_b = read_sse(
    b + "/api/v1/runs/" + run_id + "/stream",
    {"Last-Event-ID": "-1"},
)
assert inst_a and inst_b and inst_a != inst_b, (inst_a, inst_b)
assert "ha-probe" in body_b, body_b
print(f"SSE 续传通过：写入 {inst_a} runId={run_id}，回放 {inst_b}")
PY

echo "全部通过。"
