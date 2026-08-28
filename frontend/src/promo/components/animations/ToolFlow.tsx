import { motion } from 'motion/react'

const steps = [
  { label: 'LLM', sub: '选择工具' },
  { label: 'getWeather("北京")', sub: '应用执行' },
  { label: '22°C 晴', sub: '回传结果' },
]

/** Tool Calling 三节点箭头流。 */
export function ToolFlow() {
  return (
    <div className="demo-panel demo-tool-flow">
      {steps.map((step, i) => (
        <div key={step.label} className="demo-tool-step">
          <motion.div
            className="demo-tool-node"
            initial={{ opacity: 0, x: -16 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: i * 0.35, duration: 0.4 }}
          >
            <strong>{step.label}</strong>
            <span>{step.sub}</span>
          </motion.div>
          {i < steps.length - 1 && (
            <motion.span
              className="demo-tool-arrow"
              animate={{ opacity: [0.3, 1, 0.3] }}
              transition={{ repeat: Infinity, duration: 1.2, delay: i * 0.2 }}
            >
              →
            </motion.span>
          )}
        </div>
      ))}
    </div>
  )
}
