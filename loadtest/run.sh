#!/usr/bin/env bash
# 工业级第五阶段：本机 k6。优先本机 CLI，否则 docker run grafana/k6。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
K6_DIR="${SCRIPT_DIR}/k6"
SCENARIO="${1:-smoke}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:1.0.0}"

usage() {
  echo "用法: $0 smoke|guardrail|rate_limit|login_limit|all" >&2
  exit 2
}

run_k6() {
  local name="$1"
  local file="${K6_DIR}/${name}.js"
  if [[ ! -f "$file" ]]; then
    echo "找不到脚本: $file" >&2
    usage
  fi
  echo "=== k6 ${name} ==="
  mkdir -p "${SCRIPT_DIR}/results"
  local summary="${SCRIPT_DIR}/results/latest-summary.json"
  if command -v k6 >/dev/null 2>&1; then
    env BASE_URL="${BASE_URL:-http://127.0.0.1:8080/ai-example}" \
      k6 run --summary-export="${summary}" \
        -e BASE_URL="${BASE_URL:-http://127.0.0.1:8080/ai-example}" \
        -e USERNAME="${USERNAME:-alice}" \
        -e PASSWORD="${PASSWORD:-demo}" \
        "$file"
    echo "摘要已写入 ${summary}"
    return
  fi
  if ! command -v docker >/dev/null 2>&1; then
    echo "未找到 k6 或 docker。安装：brew install k6  或安装 Docker Desktop。" >&2
    exit 1
  fi
  local docker_url="${BASE_URL:-}"
  local network_flag=""
  if [[ "$(uname -s)" == "Darwin" ]]; then
    docker_url="${docker_url:-http://host.docker.internal:8080/ai-example}"
  else
    docker_url="${docker_url:-http://127.0.0.1:8080/ai-example}"
    network_flag="--network host"
  fi
  # network_flag 可能为空；不用空数组，避免 macOS bash 3.2 + set -u 报 unbound
  # shellcheck disable=SC2086
  docker run --rm ${network_flag} \
    -e BASE_URL="${docker_url}" \
    -e USERNAME="${USERNAME:-alice}" \
    -e PASSWORD="${PASSWORD:-demo}" \
    -v "${K6_DIR}:/scripts:ro" \
    -v "${SCRIPT_DIR}/results:/results" \
    "${K6_IMAGE}" run --summary-export=/results/latest-summary.json "/scripts/${name}.js"
  echo "摘要已写入 ${SCRIPT_DIR}/results/latest-summary.json"
}

case "$SCENARIO" in
  smoke|guardrail|rate_limit|login_limit)
    run_k6 "$SCENARIO"
    ;;
  all)
    failed=0
    for name in smoke guardrail rate_limit login_limit; do
      if ! run_k6 "$name"; then
        failed=1
      fi
    done
    exit "$failed"
    ;;
  *)
    usage
    ;;
esac
