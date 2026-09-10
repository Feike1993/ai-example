import http from 'k6/http'

/**
 * 工业级压测公共配置。默认打本机 Java，不经过 Vite。
 *
 * 环境变量：BASE_URL、USERNAME、PASSWORD。
 */
export const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080/ai-example'
export const username = __ENV.USERNAME || 'alice'
export const password = __ENV.PASSWORD || 'demo'

/** 与 ProductionGuardrail / E2E 相同的演示敏感词问句，走 input_deny，不打 LLM。 */
export const DENY_QUESTION = '请解释违禁演示词的含义'

/**
 * 换 JWT。供 setup() 调用一次，VU 复用，避免打光登录桶。
 *
 * @returns {string} 访问令牌
 */
export function login() {
  const res = httpPostJson('/api/v1/auth/token', { username, password })
  if (res.status !== 200) {
    throw new Error(`登录失败 HTTP ${res.status}: ${res.body}`)
  }
  const token = res.json('token')
  if (!token) {
    throw new Error('登录响应没有 token')
  }
  return token
}

/**
 * 带 Bearer 的 JSON 头。
 *
 * @param {string} token JWT
 * @returns {Record<string, string>}
 */
export function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  }
}

/**
 * 读响应头（k6 对大小写不统一）。
 *
 * @param {import('k6/http').RefinedResponse} res 响应
 * @param {string} name 头名
 * @returns {string|undefined}
 */
export function header(res, name) {
  const want = name.toLowerCase()
  for (const [key, value] of Object.entries(res.headers || {})) {
    if (key.toLowerCase() === want) {
      return value
    }
  }
  return undefined
}

/**
 * 读错误体 code，解析失败返回空串。
 *
 * @param {import('k6/http').RefinedResponse} res 响应
 * @returns {string}
 */
export function errorCode(res) {
  try {
    return String(res.json('code') || '')
  } catch (ex) {
    return ''
  }
}

/**
 * POST JSON 到 context-path 下的相对路径。
 *
 * @param {string} path 如 /api/v1/chat
 * @param {unknown} body 请求体
 * @param {Record<string, string>} [headers] 额外头
 * @returns {import('k6/http').RefinedResponse}
 */
export function httpPostJson(path, body, headers) {
  return http.post(`${baseUrl}${path}`, JSON.stringify(body), {
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
  })
}
