import type { ReactNode } from 'react'
import { motion } from 'motion/react'
import type { SampleMeta } from '../../shared/samples'

type SampleSectionProps = {
  sample: SampleMeta
  animation: ReactNode
}

/** 单样例内容区：侧栏已有名称，右侧只讲卖点、原理与动效。 */
export function SampleSection({ sample, animation }: SampleSectionProps) {
  return (
    <motion.section
      id={sample.id}
      className="sample-section"
      initial={{ opacity: 0, y: 32 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-10% 0px' }}
      transition={{ duration: 0.45 }}
    >
      <div className="sample-section-copy">
        <div className="sample-eyebrow">
          <span className="sample-index-inline">{String(sample.index).padStart(2, '0')}</span>
          <span className="sample-eyebrow-sep">·</span>
          <span>第 {sample.phase} 期</span>
        </div>
        <h2 className="sample-tagline">{sample.tagline}</h2>
        <p className="sample-body">{sample.body}</p>
        <ul className="sample-concepts" aria-label="关键概念">
          {sample.concepts.map((c) => (
            <li key={c} className="concept-pill">
              {c}
            </li>
          ))}
        </ul>
        <div className="sample-meta">
          <code className="sample-endpoint">{sample.endpoint}</code>
          <a className="sample-playground-link" href={`/index.html#${sample.id}`}>
            在 Playground 中试用 →
          </a>
        </div>
      </div>
      <div className="sample-animation" aria-label={`${sample.label} 演示`}>
        {animation}
      </div>
    </motion.section>
  )
}
