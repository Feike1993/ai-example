import { motion } from 'motion/react'
import { useEffect, useState } from 'react'

const phases = ['Perceive', 'Reason', 'Act', 'Observe']

/** ReAct 环旋转 + maxSteps 熔断演示。 */
export function ReactLoop() {
  const [step, setStep] = useState(1)
  const [fused, setFused] = useState(false)

  useEffect(() => {
    const tick = window.setInterval(() => {
      setStep((s) => {
        if (s >= 3) {
          setFused(true)
          window.setTimeout(() => setFused(false), 800)
          return 1
        }
        return s + 1
      })
    }, 1400)
    return () => window.clearInterval(tick)
  }, [])

  return (
    <div className="demo-panel demo-react-loop">
      <div className={`demo-react-ring${fused ? ' is-fused' : ''}`}>
        {phases.map((p, i) => (
          <motion.span
            key={p}
            className={`demo-react-node${step === i + 1 ? ' is-active' : ''}`}
            style={{ transform: `rotate(${i * 90}deg) translate(0, -72px)` }}
          >
            {p}
          </motion.span>
        ))}
        <span className="demo-react-center">step {step}/3</span>
      </div>
      {fused && <div className="demo-fuse-badge">maxSteps 熔断</div>}
    </div>
  )
}
