/**
 * 打穿登录 IP 桶：同 IP 连打 /auth/token，期望出现 429。默认 10 / 60s，故迭代 ≥15。
 */
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { errorCode, header, httpPostJson, password, username } from './lib/config.js'

const loginLimited = new Counter('prod_login_limited')

export const options = {
  vus: 1,
  iterations: 15,
  thresholds: {
    prod_login_limited: ['count>=1'],
  },
}

export default function () {
  http.setResponseCallback(http.expectedStatuses(200, 429))
  const res = httpPostJson('/api/v1/auth/token', { username, password })
  const ok = res.status === 200 || res.status === 429
  check(res, { '200 或 429': () => ok })
  if (res.status === 429) {
    loginLimited.add(1)
    check(res, {
      'rate_limited': (r) => errorCode(r) === 'rate_limited',
      'Retry-After': (r) => Boolean(header(r, 'Retry-After')),
    })
  }
}
