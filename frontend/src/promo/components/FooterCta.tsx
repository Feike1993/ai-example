import { baselineSamples } from '../../shared/samples'

/** 页脚 CTA 与文档链接。 */
export function FooterCta() {
  return (
    <footer className="promo-footer">
      <div className="promo-footer-formula">
        <p className="promo-formula-main">Agent = LLM + Planning + Memory + Tools</p>
        <p className="promo-formula-evolve">
          Memory 演进：当轮消息（第一期）→ trim / summarize（第三期）→ 多 Agent 信息边界
        </p>
      </div>
      <div className="promo-footer-cta">
        <a className="promo-btn promo-btn--primary" href="/index.html">
          进入 Playground
        </a>
        <a
          className="promo-btn promo-btn--ghost"
          href="https://github.com/Feike1993/ai-example/blob/main/docs/learning-path.md"
          target="_blank"
          rel="noreferrer"
        >
          学习路径
        </a>
        <a
          className="promo-btn promo-btn--ghost"
          href="/promo/opensource-poster-1080.png"
          download="ai-example-opensource-poster-1080.png"
        >
          下载宣传图
        </a>
      </div>
      <nav className="promo-footer-docs" aria-label="样例文档">
        {baselineSamples.map((s) => (
          <a
            key={s.id}
            href={`https://github.com/Feike1993/ai-example/blob/main/${s.docPath}`}
            target="_blank"
            rel="noreferrer"
          >
            {s.label}
          </a>
        ))}
      </nav>
      <p className="promo-footer-copy">ai-example · 开源 Agent 学习 Cookbook</p>
    </footer>
  )
}
