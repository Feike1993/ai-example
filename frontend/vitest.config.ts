import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

/**
 * 与 vite.config.ts 分开：那份配置带多入口构建和后端代理，测试环境两者都不需要，
 * 混在一起还会让 Vitest 每次都去解析四个 HTML 入口。
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})
