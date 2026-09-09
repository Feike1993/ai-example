import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发服务器把 /ai-example 转到 Java 样例端口，避免浏览器 CORS；
 * SSE 关闭代理缓冲，否则 token 会攒成一块才到达前端。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: 'index.html',
        // 工业级区独立入口：与教学样例场共享组件与代理，但页面结构、导航、视觉完全分开
        industrial: 'industrial.html',
        promo: 'promo.html',
        poster: 'poster.html',
        endcard: 'endcard.html',
      },
    },
  },
  server: {
    /** 显式绑 IPv4，避免仅监听 ::1 时 127.0.0.1 打不开 */
    host: '127.0.0.1',
    port: 5173,
    /** 日常 pnpm dev 打开浏览器；E2E 脚本设 VITE_E2E=1，避免 CI 弹窗 */
    open: process.env.VITE_E2E ? false : true,
    proxy: {
      '/ai-example': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const contentType = proxyRes.headers['content-type']
            if (typeof contentType === 'string' && contentType.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        },
      },
    },
  },
})
