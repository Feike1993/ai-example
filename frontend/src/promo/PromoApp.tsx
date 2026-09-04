import { useMemo, type ReactNode } from 'react'
import { motion } from 'motion/react'
import { baselineSamples, type SampleId } from '../shared/samples'
import { ContextBudget } from './components/animations/ContextBudget'
import { JsonMorph } from './components/animations/JsonMorph'
import { McpUsbDemo } from './components/animations/McpUsbDemo'
import { MultiAgentOrchestra } from './components/animations/MultiAgentOrchestra'
import { RagPipeline } from './components/animations/RagPipeline'
import { ReactLoop } from './components/animations/ReactLoop'
import { SseDemo } from './components/animations/SseDemo'
import { ToolFlow } from './components/animations/ToolFlow'
import { AdvancedGroupsGallery } from './components/AdvancedGroupsGallery'
import { FooterCta } from './components/FooterCta'
import { HeroSpiral } from './components/HeroSpiral'
import { PhaseDivider } from './components/PhaseDivider'
import { PhaseTimeline } from './components/PhaseTimeline'
import { SampleSection } from './components/SampleSection'
import { StickyNav } from './components/StickyNav'
import { TechStack } from './components/TechStack'
import { useHashNavigation, useScrollSpy } from './hooks/useScrollSpy'
import type { PhaseId } from '../shared/brand'

const animationMap: Record<SampleId, ReactNode> = {
  chat: <SseDemo />,
  structured: <JsonMorph />,
  tools: <ToolFlow />,
  agent: <ReactLoop />,
  mcp: <McpUsbDemo />,
  rag: <RagPipeline />,
  context: <ContextBudget />,
  multiagent: <MultiAgentOrchestra />,
}

/** 宣传页主布局：Hero → 基础 8 样例 Journey → 进阶五组图墙。 */
export default function PromoApp() {
  const ids = useMemo(() => baselineSamples.map((s) => s.id), [])
  const { activeId, setActiveId } = useScrollSpy(ids)
  useHashNavigation(setActiveId)

  let lastPhase: PhaseId | null = null

  return (
    <div className="promo-root">
      <header className="promo-hero">
        <motion.div
          className="promo-hero-content"
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
        >
          <div className="wordmark promo-wordmark">
            <span className="wordmark-mark">AI</span>
            <span className="wordmark-rest">Example</span>
          </div>
          <span className="promo-version">v0.2.0 baseline · 进阶至第十三期</span>
          <h1>
            <span className="promo-hero-line">8 样例走完 Agent</span>
            <span className="promo-hero-line">基础闭环</span>
          </h1>
          <p className="promo-hero-sub">
            先打通 Chat → 结构化 → Tools → Agent → MCP → RAG → 上下文 → 多 Agent；进阶五组在页末按主题展开底层逻辑。
          </p>
          <div className="promo-hero-cta">
            <a className="promo-btn promo-btn--primary" href="#baseline">
              看基础闭环
            </a>
            <a className="promo-btn promo-btn--ghost-light" href="#advanced-groups">
              看进阶五组
            </a>
            <a className="promo-btn promo-btn--ghost-light" href="/index.html">
              进入 Playground
            </a>
          </div>
        </motion.div>
        <HeroSpiral />
      </header>

      <section className="promo-overview" aria-label="学习层次">
        <span className="promo-overview-chip promo-overview-chip--baseline">基础 · 8 样例 · v0.2.0</span>
        <span className="promo-overview-chip promo-overview-chip--advanced">进阶 · 五组逻辑 · 第四～十三期</span>
        <span>Java + Python 对照</span>
        <span>Playground 即点即试</span>
      </section>

      <StickyNav activeId={activeId} variant="chips" />

      <div id="baseline" className="promo-stage promo-stage--baseline">
        <div className="promo-stage-banner">
          <span className="promo-stage-badge">基础</span>
          <div>
            <h2 className="promo-stage-title">基础闭环 Journey</h2>
            <p className="promo-stage-desc">一至三期 8 样例，动效讲清每条链路；侧栏可跳转。</p>
          </div>
        </div>
        <div className="promo-journey">
          <aside className="promo-journey-nav">
            <StickyNav activeId={activeId} variant="sidebar" />
          </aside>
          <div className="promo-journey-content">
            {baselineSamples.map((sample) => {
              const phase = sample.phase as PhaseId
              const showDivider = lastPhase !== phase
              lastPhase = phase
              return (
                <div key={sample.id}>
                  {showDivider && <PhaseDivider phase={phase} />}
                  <SampleSection sample={sample} animation={animationMap[sample.id]} />
                </div>
              )
            })}
            <PhaseTimeline />
            <TechStack />
          </div>
        </div>
      </div>

      <div className="promo-stage promo-stage--advanced">
        <AdvancedGroupsGallery />
      </div>

      <FooterCta />
    </div>
  )
}
