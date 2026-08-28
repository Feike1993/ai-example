import { motion } from 'motion/react'
import { useEffect, useState } from 'react'

/** RAG 流水线：分块 → 向量 → 检索 → 回答。 */
export function RagPipeline() {
  const [stage, setStage] = useState(0)
  const stages = ['文档分块', 'Embedding', 'pgvector 检索', 'LLM + sources']

  useEffect(() => {
    const t = window.setInterval(() => setStage((s) => (s + 1) % 5), 1200)
    return () => window.clearInterval(t)
  }, [])

  return (
    <div className="demo-panel demo-rag">
      <div className="demo-rag-pipeline">
        {stages.map((label, i) => (
          <motion.div
            key={label}
            className={`demo-rag-step${stage === i ? ' is-active' : ''}${stage > i ? ' is-done' : ''}`}
            animate={stage === i ? { scale: [1, 1.05, 1] } : {}}
            transition={{ duration: 0.5 }}
          >
            {label}
          </motion.div>
        ))}
      </div>
      <div className="demo-rag-grid" aria-hidden="true">
        {Array.from({ length: 24 }).map((_, i) => (
          <span key={i} className={stage >= 2 && i % 7 === 3 ? ' is-hit' : ''} />
        ))}
      </div>
      {stage === 4 && (
        <motion.div className="demo-rag-empty" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
          空检索 → retrievalEmpty 拒答（不编造）
        </motion.div>
      )}
    </div>
  )
}
