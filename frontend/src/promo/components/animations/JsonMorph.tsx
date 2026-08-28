import { motion } from 'motion/react'
import { useEffect, useState } from 'react'

/** 结构化输出：文本 morph 为 JSON 工单。 */
export function JsonMorph() {
  const [phase, setPhase] = useState<'input' | 'json'>('input')

  useEffect(() => {
    const t = window.setInterval(() => {
      setPhase((p) => (p === 'input' ? 'json' : 'input'))
    }, 3200)
    return () => window.clearInterval(t)
  }, [])

  return (
    <div className="demo-panel demo-json-morph">
      <motion.div
        key={phase}
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0 }}
        transition={{ duration: 0.35 }}
        className={phase === 'input' ? 'demo-user-msg' : 'demo-json-card'}
      >
        {phase === 'input' ? (
          <>「空调不制冷，房间 801，希望明天上午上门」</>
        ) : (
          <pre>{`{
  "title": "空调维修",
  "room": "801",
  "priority": "normal",
  "slot": "明天上午"
}`}</pre>
        )}
      </motion.div>
      <div className="demo-meta">JSON Schema 约束 · 失败自动重试</div>
    </div>
  )
}
