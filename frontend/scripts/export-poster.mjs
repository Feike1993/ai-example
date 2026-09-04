#!/usr/bin/env node
/**
 * 导出宣传图 PNG。
 * 用法：
 *   node scripts/export-poster.mjs              # 方版开源图
 *   node scripts/export-poster.mjs endcard      # 竖版仓库结尾卡
 */
import { execFileSync, spawn } from 'node:child_process'
import { mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.join(root, '..')
const outDir = path.join(frontendRoot, 'public', 'promo')
const port = 5173

const targets = {
  poster: {
    url: `http://127.0.0.1:${port}/poster.html`,
    outPath: path.join(outDir, 'opensource-poster-1080.png'),
    width: 1080,
    height: 1080,
  },
  endcard: {
    url: `http://127.0.0.1:${port}/endcard.html`,
    outPath: path.join(outDir, 'repo-endcard-1080x1920.png'),
    width: 1080,
    height: 1920,
  },
}

const kind = process.argv[2] === 'endcard' ? 'endcard' : 'poster'
const target = targets[kind]

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
    const res = await fetch(target.url, { signal: AbortSignal.timeout(2000) })
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
      `--window-size=${target.width},${target.height}`,
      '--force-device-scale-factor=2',
      `--screenshot=${target.outPath}`,
      target.url,
    ],
    { stdio: 'inherit' },
  )
}

async function main() {
  const chrome = findChrome()
  if (!chrome) {
    console.error('未找到 Chrome。请安装 Google Chrome 或 Chromium。')
    console.error(`也可手动打开对应 HTML 后截图保存为 ${target.outPath}`)
    process.exit(1)
  }

  let viteChild = null
  try {
    viteChild = await ensureServer()
    screenshot(chrome)
    console.log(`\n已导出 → ${target.outPath}`)
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
