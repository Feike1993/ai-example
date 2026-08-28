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

/** 宣传页主布局：Hero + sticky 侧栏 + 8 样例 Journey。 */
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
          <span className="promo-version">v0.2.0 baseline</span>
          <h1>
            <span className="promo-hero-line">8 样例走完 Agent</span>
            <span className="promo-hero-line">基础闭环</span>
          </h1>
          <p className="promo-hero-sub">
            Chat → 结构化 → Tools → Agent → MCP → RAG → 上下文 → 多 Agent
          </p>
          <div className="promo-hero-cta">
            <a className="promo-btn promo-btn--primary" href="/index.html">
              进入 Playground
            </a>
            <a
              className="promo-btn promo-btn--ghost-light"
              href="https://github.com/Feike1993/ai-example/blob/main/docs/learning-path.md"
              target="_blank"
              rel="noreferrer"
            >
              查看学习路径
            </a>
          </div>
        </motion.div>
        <HeroSpiral />
      </header>

      <section className="promo-overview">
        {['8 样例可跑通', 'Java + Python 对照', 'Playground 即点即试', '三期递进'].map((t) => (
          <span key={t}>{t}</span>
        ))}
      </section>

      <StickyNav activeId={activeId} variant="chips" />

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
          <FooterCta />
        </div>
      </div>
    </div>
  )
}
