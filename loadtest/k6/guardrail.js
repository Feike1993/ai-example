/**
 * 输入护栏：POST /chat 带演示敏感词，期望 422 input_deny，不打 LLM。
 */
import http from 'k6/http'
import { check } from 'k6'
import { authHeaders, DENY_QUESTION, errorCode, httpPostJson, login } from './lib/config.js'

export const options = {
  vus: 1,
  iterations: 5,
  thresholds: {
    checks: ['rate==1'],
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
  http.setResponseCallback(http.expectedStatuses(422))
  const res = httpPostJson('/api/v1/chat', { question: DENY_QUESTION }, authHeaders(data.token))
  check(res, {
    '422': (r) => r.status === 422,
    'input_deny': (r) => errorCode(r) === 'input_deny',
  })
}
