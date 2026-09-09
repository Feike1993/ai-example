import { expect, test } from '@playwright/test'
import {
  EMPTY_REFUSAL,
  fetchToken,
  hasChatKey,
  hasEmbeddingKey,
  login,
  readSessionId,
} from './helpers'

test.describe('工业级 @keys E2E', () => {
  test.beforeEach(() => {
    test.skip(!hasEmbeddingKey(), '需要 PROVIDER_DASHSCOPE_API_KEY 或 E2E_HAS_EMBEDDING=1')
  })

  test('@keys 空检索返回固定拒答', async ({ page }) => {
    await login(page, 'alice')
    const nonce = `e2e-empty-${Date.now()}-zxqwvutsrqponmlkjihgfedcba`
    await page.getByLabel('问题').fill(nonce)
    await page.getByRole('button', { name: '流式提问' }).click()
    await expect(page.getByTestId('stream-status')).toContainText('已完成', { timeout: 60_000 })
    const refusal = page.getByText(EMPTY_REFUSAL)
    const sources = page.getByText(/检索来源（[1-9]/)
    if (await sources.isVisible().catch(() => false)) {
      test.skip(true, '生产语料已入库，向量路总会返回近邻，无法验证空检索')
    }
    await expect(refusal).toBeVisible()
  })

  test('@keys admin 可重建语料', async ({ page }) => {
    await login(page, 'admin')
    await page.getByRole('button', { name: '重建语料' }).click()
    await expect(page.getByText(/已重建语料/)).toBeVisible({ timeout: 120_000 })
    await expect(page.getByTestId('result-error')).toHaveCount(0)
  })

  test('@keys bob 看不到 alice 的会话轮次', async ({ page, request }) => {
    const secret = `alice-secret-turn-${Date.now()}`
    await login(page, 'alice')
    await page.getByLabel('问题').fill(secret)
    await page.getByRole('button', { name: '流式提问' }).click()
    await expect(page.getByTestId('stream-status')).toContainText(/已完成|未完成/, { timeout: 60_000 })
    const sessionId = await readSessionId(page)
    expect(sessionId, 'alice 提问后应拿到 sessionId').toBeTruthy()

    const bobToken = await fetchToken(request, 'bob')
    const cross = await request.get(
      `/ai-example/api/v1/sessions/${encodeURIComponent(sessionId!)}`,
      { headers: { Authorization: `Bearer ${bobToken}` } },
    )
    expect(cross.status(), '跨租户不暴露存在性，一律 404').toBe(404)

    await page.getByTestId('nav-security').click()
    await page.getByTestId('logout').click()
    await login(page, 'bob')
    await page.evaluate((id) => {
      localStorage.setItem('ai-example.production.sessionId', id)
    }, sessionId!)
    await page.reload()
    await expect(page.getByTestId('identity-line')).toContainText('bob · tenant-b')
    await expect(page.getByText(secret)).toHaveCount(0)
    await expect(page.getByTestId('session-meta')).toContainText(sessionId!)
  })

  test('@keys ingest 后相关问题出现正文', async ({ page }) => {
    test.skip(!hasChatKey(), '完整生成还需要 Chat Provider Key')
    await login(page, 'admin')
    await page.getByRole('button', { name: '重建语料' }).click()
    await expect(page.getByText(/已重建语料/)).toBeVisible({ timeout: 120_000 })
    await page.getByTestId('nav-security').click()
    await page.getByTestId('logout').click()
    await login(page, 'alice')
    await page.getByLabel('问题').fill('这个项目的 RAG 是怎么做的？')
    await page.getByRole('button', { name: '流式提问' }).click()
    await expect(page.getByTestId('stream-status')).toContainText('已完成', { timeout: 120_000 })
    await expect(page.getByText(EMPTY_REFUSAL)).toHaveCount(0)
    await expect(page.getByText(/检索来源/)).toBeVisible()
  })
})
