import { brand } from '../shared/brand'

/**
 * 抖音/短视频片尾定格用：竖版 1080×1920。
 * 信息尽量少：字标 + 大号仓库地址 + 一句邀请。
 */
export function RepoEndCard() {
  return (
    <div className="endcard-canvas" style={{ background: brand.heroDark }}>
      <div className="endcard-glow" aria-hidden="true" />
      <div className="endcard-inner">
        <div className="endcard-wordmark">
          <span className="endcard-wordmark-ai">AI</span>
          <span>Example</span>
        </div>

        <p className="endcard-hint">仓库</p>

        <p className="endcard-repo">
          <span className="endcard-repo-host">github.com/Feike1993/</span>
          <span className="endcard-repo-name">ai-example</span>
        </p>

        <p className="endcard-invite">一起学 · 有问题提 issue</p>
      </div>
    </div>
  )
}
