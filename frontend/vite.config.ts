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
        promo: 'promo.html',
        poster: 'poster.html',
      },
    },
  },
  server: {
    port: 5173,
    /** 启动 dev 时用系统默认浏览器打开（非 IDE 内嵌预览） */
    open: true,
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
