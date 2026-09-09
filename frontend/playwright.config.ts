import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173'
const slowMo = Number(process.env.PLAYWRIGHT_SLOWMO || 0)

/**
 * 工业级 Playwright：默认套件不打 Chat LLM；@keys 需 Embedding。
 * vite preview 没有 /ai-example 代理，E2E 只用 Vite 开发服或 Compose :8088。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    launchOptions: slowMo > 0 ? { slowMo } : undefined,
  },
  webServer: process.env.PLAYWRIGHT_BASE_URL
    ? undefined
    : {
        command: 'pnpm run dev:e2e',
        url: 'http://127.0.0.1:5173/industrial.html',
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
  projects: [
    { name: 'chromium', grepInvert: /@keys/ },
    { name: 'keys', grep: /@keys/ },
  ],
})
