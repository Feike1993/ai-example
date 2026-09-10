/**
 * 打穿 chat 令牌桶：同一主体连打护栏题（仍不打 LLM），期望出现 429 rate_limited。
 * 默认容量 30 / 60s，故迭代 ≥40。
 */
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { authHeaders, DENY_QUESTION, errorCode, header, httpPostJson, login } from './lib/config.js'

const rateLimited = new Counter('prod_rate_limited')

export const options = {
  vus: 1,
  iterations: 40,
  thresholds: {
    prod_rate_limited: ['count>=1'],
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
  http.setResponseCallback(http.expectedStatuses(422, 429))
  const res = httpPostJson('/api/v1/chat', { question: DENY_QUESTION }, authHeaders(data.token))
  const ok = res.status === 422 || res.status === 429
  check(res, { '422 或 429': () => ok })
  if (res.status === 429) {
    rateLimited.add(1)
    check(res, {
      'rate_limited': (r) => errorCode(r) === 'rate_limited',
      'Retry-After': (r) => Boolean(header(r, 'Retry-After')),
    })
  }
}
