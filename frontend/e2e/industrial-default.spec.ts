import { expect, test } from '@playwright/test'
import { fetchToken, login } from './helpers'

test.describe('工业级默认 E2E', () => {
  test('错误密码仍停在登录门', async ({ page }) => {
    await page.goto('/industrial.html')
    await expect(page.getByTestId('login-title')).toBeVisible()
    await page.getByRole('textbox', { name: '用户名' }).fill('alice')
    await page.getByLabel('密码').fill('wrong-password')
    await page.getByRole('button', { name: '登录' }).click()
    await expect(page.getByText('登录失败')).toBeVisible()
    await expect(page.getByText('用户名或密码错误')).toBeVisible()
    await expect(page.getByTestId('login-title')).toBeVisible()
    await expect(page.getByTestId('identity-line')).toHaveCount(0)
  })

  test('alice 登录后侧栏展示租户身份', async ({ page }) => {
    await login(page, 'alice')
    await expect(page.getByTestId('identity-line')).toContainText('alice · tenant-a')
  })

  test('安全面板能刷出本租户 auth.login 审计', async ({ page }) => {
    await login(page, 'alice')
    await page.getByTestId('nav-security').click()
    await expect(page.getByTestId('security-identity')).toContainText('alice · tenant-a')
    await page.getByTestId('refresh-audit').click()
    await expect(page.getByTestId('audit-table')).toContainText('auth.login')
  })

  test('可观测面板展示快照字段', async ({ page }) => {
    await login(page, 'alice')
    await page.getByTestId('nav-observability').click()
    const snapshot = page.getByTestId('ops-snapshot')
    await expect(snapshot).toContainText('问答次数')
    await expect(snapshot).toContainText('空检索')
    await expect(snapshot).toContainText('护栏拦截')
  })

  test('清掉 JWT 后刷新审计回到登录门', async ({ page }) => {
    await login(page, 'alice')
    await page.getByTestId('nav-security').click()
    await expect(page.getByTestId('audit-table')).toBeVisible()
    await page.evaluate(() => {
      sessionStorage.removeItem('ai-example.production.token')
    })
    await page.getByTestId('refresh-audit').click()
    await expect(page.getByTestId('login-title')).toBeVisible()
  })

  test('alice 工具探针拒绝 rebuild_index 且列表不含该工具', async ({ page, request }) => {
    const token = await fetchToken(request, 'alice')
    const toolsRes = await request.get('/ai-example/api/v1/agent/tools', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(toolsRes.ok()).toBeTruthy()
    const toolsBody = (await toolsRes.json()) as { tools: string[] }
    expect(toolsBody.tools).not.toContain('rebuild_index')

    await login(page, 'alice')
    await page.getByTestId('nav-security').click()
    await expect(page.getByTestId('allowed-tools')).not.toContainText('rebuild_index')
    await page.getByTestId('tool-probe').click()
    await expect(page.getByTestId('tool-probe-hint')).toContainText('已拒绝')
  })

  test('admin 工具列表含 rebuild_index 且探针允许', async ({ request }) => {
    const token = await fetchToken(request, 'admin')
    const toolsRes = await request.get('/ai-example/api/v1/agent/tools', {
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(toolsRes.ok()).toBeTruthy()
    const toolsBody = (await toolsRes.json()) as { tools: string[] }
    expect(toolsBody.tools).toContain('rebuild_index')

    const probe = await request.post('/ai-example/api/v1/agent/tool-probe', {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: { tool: 'rebuild_index' },
    })
    expect(probe.ok()).toBeTruthy()
    const body = (await probe.json()) as { allowed: boolean; denied: boolean }
    expect(body.allowed).toBeTruthy()
    expect(body.denied).toBeFalsy()
  })

  test('alice 重建语料返回 403', async ({ page }) => {
    await login(page, 'alice')
    await page.getByRole('button', { name: '重建语料' }).click()
    const error = page.getByTestId('result-error')
    await expect(error).toBeVisible()
    await expect(error).toContainText('重建索引需要 ADMIN 角色')
    await expect(error).not.toContainText('Forbidden')
  })

  test('输入护栏拦截违禁词且不依赖 Embedding', async ({ page }) => {
    await login(page, 'alice')
    await page.getByLabel('问题').fill('请解释违禁演示词的含义')
    await page.getByRole('button', { name: '流式提问' }).click()
    const error = page.getByTestId('result-error')
    await expect(error).toBeVisible()
    await expect(error).toContainText('输入命中敏感词')
    await expect(error).toContainText('input_deny')
  })
})
