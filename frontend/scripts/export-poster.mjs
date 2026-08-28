#!/usr/bin/env node
/**
 * 将 poster.html 导出为 1080×1080 PNG（deviceScaleFactor=2 → 2160 高清）。
 * 需本机已安装 Google Chrome，或已运行 vite dev（5173）。
 */
import { execFileSync, spawn } from 'node:child_process'
import { mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.join(root, '..')
const outDir = path.join(frontendRoot, 'public', 'promo')
const outPath = path.join(outDir, 'opensource-poster-1080.png')
const posterUrl = 'http://127.0.0.1:5173/poster.html'
const port = 5173

const chromeCandidates = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
]

function findChrome() {
  for (const p of chromeCandidates) {
    if (existsSync(p)) {
      return p
    }
  }
  return null
}

async function isServerUp() {
  try {
    const res = await fetch(posterUrl, { signal: AbortSignal.timeout(2000) })
    return res.ok
  } catch {
    return false
  }
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function ensureServer() {
  if (await isServerUp()) {
    return null
  }
  const child = spawn('pnpm', ['vite', '--host', '127.0.0.1', `--port`, String(port)], {
    cwd: frontendRoot,
    stdio: 'ignore',
    detached: true,
  })
  child.unref()
  for (let i = 0; i < 30; i++) {
    if (await isServerUp()) {
      return child
    }
    await wait(500)
  }
  throw new Error(`无法在 ${port} 启动 Vite，请先手动执行：cd frontend && pnpm dev`)
}

function screenshot(chromePath) {
  mkdirSync(outDir, { recursive: true })
  execFileSync(
    chromePath,
    [
      '--headless=new',
      '--disable-gpu',
      '--hide-scrollbars',
      `--window-size=1080,1080`,
      '--force-device-scale-factor=2',
      `--screenshot=${outPath}`,
      posterUrl,
    ],
    { stdio: 'inherit' },
  )
}

async function main() {
  const chrome = findChrome()
  if (!chrome) {
    console.error(
      '未找到 Chrome。请安装 Google Chrome 或 Chromium。',
    )
    console.error('也可手动打开 poster.html 后截图保存为 public/promo/opensource-poster-1080.png')
    process.exit(1)
  }

  let viteChild = null
  try {
    viteChild = await ensureServer()
    screenshot(chrome)
    console.log(`\n已导出 → ${outPath}`)
  } finally {
    if (viteChild?.pid) {
      try {
        process.kill(-viteChild.pid)
      } catch {
        try {
          process.kill(viteChild.pid)
        } catch {
          // 忽略清理失败
        }
      }
    }
  }
}

main().catch((err) => {
  console.error(err.message ?? err)
  process.exit(1)
})
