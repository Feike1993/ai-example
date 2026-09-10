/**
 * 廉价读冒烟：JWT 后打 /me、/audit、/ops/snapshot，alice ingest 期望 403。
 * 这些路径不走 chat 令牌桶，也不打 LLM / Embedding。
 */
import http from 'k6/http'
import { check, sleep } from 'k6'
import { authHeaders, baseUrl, errorCode, login } from './lib/config.js'

export const options = {
  vus: 8,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    checks: ['rate>0.99'],
  },
}

/**
 * @returns {{ token: string }}
 */
export function setup() {
  return { token: login() }
}

/**
 * @param {{ token: string }} data setup 返回
 */
export default function (data) {
  http.setResponseCallback(http.expectedStatuses(200, 403))
  const headers = authHeaders(data.token)

  const me = http.get(`${baseUrl}/api/v1/me`, { headers })
  check(me, { 'me 200': (r) => r.status === 200 })

  const audit = http.get(`${baseUrl}/api/v1/audit`, { headers })
  check(audit, { 'audit 200': (r) => r.status === 200 })

  const ops = http.get(`${baseUrl}/api/v1/ops/snapshot`, { headers })
  check(ops, { 'ops 200': (r) => r.status === 200 })

  const ingest = http.post(`${baseUrl}/api/v1/rag/ingest`, null, { headers })
  check(ingest, {
    'ingest 403': (r) => r.status === 403,
    'ingest_forbidden': (r) => errorCode(r) === 'ingest_forbidden',
  })

  sleep(0.2)
}
