# 前端 playground

需要 [pnpm](https://pnpm.io/)（`corepack enable` 即可）。Java 服务先在 8080 启动。

```bash
pnpm install
pnpm dev
```

打开 http://localhost:5173 。详情见仓库根目录 README。

## 多入口

```bash
pnpm dev
```

- Playground（教学样例）：http://localhost:5173/
- 工业级（`/api/v1` 生产链路）：http://localhost:5173/industrial.html
- 基础闭环宣传页：http://localhost:5173/promo.html

构建后独立产物：`dist/index.html`、`dist/industrial.html`、`dist/promo.html`。

```bash
pnpm test          # Vitest 组件/单测
pnpm test:e2e        # 工业级 Playwright 默认套件（无头）
pnpm test:e2e:headed # 弹出浏览器，放慢点击，便于看操作过程
pnpm test:e2e:ui     # Playwright UI：逐步点开每条用例
pnpm test:e2e:keys   # 空检索 / ingest / 跨租户（还需 Embedding Key）
```

工业级第四阶段的 E2E 不是教学第四期 Hybrid RAG。`vite preview` 没有 API 代理，不要用来跑 Playwright。
