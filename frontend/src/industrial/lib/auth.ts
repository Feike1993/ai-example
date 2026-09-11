/** sessionStorage 键：工业级 JWT。刷新页保留，关标签页即清。 */
const TOKEN_KEY = 'ai-example.production.token'
const USER_KEY = 'ai-example.production.user'
const RATE_KEY = 'ai-example.production.rateRemaining'
const TRACE_KEY = 'ai-example.production.lastTraceId'
const RUN_KEY = 'ai-example.production.lastRunId'

/** 401 时广播，外壳清 token 回登录。 */
export const UNAUTHORIZED_EVENT = 'production:unauthorized'

export type ProductionUser = {
  username: string
  tenant: string
  roles: string[]
}

/** 登录页与安全面板共用的演示账号说明。密码均为 demo。 */
export const DEMO_ACCOUNTS: Array<{ username: string; tenant: string; roles: string; hint: string }> = [
  { username: 'alice', tenant: 'tenant-a', roles: 'USER', hint: '普通用户' },
  { username: 'bob', tenant: 'tenant-b', roles: 'USER', hint: '另一租户，看不到 alice 的会话' },
  { username: 'admin', tenant: 'tenant-a', roles: 'ADMIN + USER', hint: '可重建索引、调用 rebuild_index' },
]

/**
 * @returns 当前访问令牌；未登录为 null
 */
export function getAccessToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

/**
 * @returns 登录时缓存的主体
 */
export function getProductionUser(): ProductionUser | null {
  const raw = sessionStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as ProductionUser
  } catch {
    return null
  }
}

/**
 * 写入登录态。
 *
 * @param token JWT
 * @param user  主体
 */
export function setSession(token: string, user: ProductionUser): void {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 清登录态，不派发事件。 */
export function clearAuth(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
}

/** 401：清 token 并通知外壳。 */
export function notifyUnauthorized(): void {
  clearAuth()
  window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
}

/**
 * @returns 带 Bearer 的请求头；未登录返回空对象
 */
export function authHeaders(): Record<string, string> {
  const token = getAccessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/**
 * 记住最近一次限流剩余，供安全面板展示。
 *
 * @param remaining 响应头 X-RateLimit-Remaining
 */
export function rememberRateRemaining(remaining: string | null): void {
  if (remaining == null || remaining === '') {
    return
  }
  sessionStorage.setItem(RATE_KEY, remaining)
}

/**
 * @returns 最近一次限流剩余
 */
export function getRateRemaining(): string | null {
  return sessionStorage.getItem(RATE_KEY)
}

/**
 * 记住最近一次 traceId，供可观测面板深链。
 *
 * @param traceId meta 事件或 snapshot 里的 id
 */
export function rememberTraceId(traceId: string | null | undefined): void {
  if (!traceId) {
    return
  }
  sessionStorage.setItem(TRACE_KEY, traceId)
}

/**
 * @returns 最近一次 traceId
 */
export function getLastTraceId(): string | null {
  return sessionStorage.getItem(TRACE_KEY)
}

/**
 * 记住最近一次 runId，供可观测面板拉步骤时间线。
 *
 * @param runId meta 事件里的 id
 */
export function rememberRunId(runId: string | null | undefined): void {
  if (!runId) {
    return
  }
  sessionStorage.setItem(RUN_KEY, runId)
}

/**
 * @returns 最近一次 runId
 */
export function getLastRunId(): string | null {
  return sessionStorage.getItem(RUN_KEY)
}
