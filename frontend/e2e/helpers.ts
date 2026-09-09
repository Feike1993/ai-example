import { expect, type APIRequestContext, type Page } from '@playwright/test'

const PRODUCTION_BASE = '/ai-example/api/v1'

export const EMPTY_REFUSAL =
  '知识库中没有检索到与该问题相关的内容，无法作答。请换个问法，或确认相关文档已入库。'

/**
 * 打开工业级入口并登录。
 *
 * @param page     Playwright 页
 * @param username 演示用户，默认 alice
 * @param password 密码，默认 demo
 */
export async function login(page: Page, username = 'alice', password = 'demo'): Promise<void> {
  await page.goto('/industrial.html')
  await expect(page.getByTestId('login-title')).toBeVisible()
  // Mantine 把 data-testid 打在 input 上，不能再 locator('input')
  await page.getByRole('textbox', { name: '用户名' }).fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page.getByTestId('identity-line')).toContainText(username)
}

/**
 * 用 HTTP 换 JWT，供跨租户 API 断言，不经过登录表单。
 *
 * @param request  Playwright request
 * @param username 演示用户
 * @param password 密码
 * @returns 访问令牌
 */
export async function fetchToken(
  request: APIRequestContext,
  username: string,
  password = 'demo',
): Promise<string> {
  const response = await request.post(`${PRODUCTION_BASE}/auth/token`, {
    data: { username, password },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  const body = (await response.json()) as { token: string }
  expect(body.token).toBeTruthy()
  return body.token
}

/**
 * 从会话元数据行解析当前 sessionId；尚未建立时返回 null。
 *
 * @param page Playwright 页
 */
export async function readSessionId(page: Page): Promise<string | null> {
  const text = await page.getByTestId('session-meta').innerText()
  const match = text.match(/会话\s+(\S+)/)
  if (!match || match[1] === '未建立') {
    return null
  }
  return match[1]
}

/**
 * 是否已配置 Embedding（空检索 / ingest 仍要打向量接口）。
 */
export function hasEmbeddingKey(): boolean {
  if (process.env.E2E_HAS_EMBEDDING === '1') {
    return true
  }
  const key = process.env.PROVIDER_DASHSCOPE_API_KEY ?? ''
  return key.length > 8 && !key.includes('your-')
}

/**
 * 是否已配置 Chat Provider Key（命中后完整生成）。
 */
export function hasChatKey(): boolean {
  const keys = [
    process.env.PROVIDER_DEEPSEEK_API_KEY,
    process.env.AI_API_KEY,
    process.env.PROVIDER_KIMI_API_KEY,
    process.env.PROVIDER_GLM_API_KEY,
  ]
  return keys.some((value) => Boolean(value && value.length > 8 && !value.includes('your-')))
}
