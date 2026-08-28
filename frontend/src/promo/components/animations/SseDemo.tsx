import { useEffect, useState } from 'react'

/** Chat SSE token 流 + TTFT 演示。 */
export function SseDemo() {
  const full = '你好！我是 ai-example 的 Chat 样例，支持 SSE 流式输出。'
  const [text, setText] = useState('')
  const [ttft, setTtft] = useState<number | null>(null)
  const [started, setStarted] = useState(false)

  useEffect(() => {
    const startTimer = window.setTimeout(() => {
      setStarted(true)
      const t0 = performance.now()
      let i = 0
      const tick = window.setInterval(() => {
        i += 1
        if (i === 1) {
          setTtft(Math.round(performance.now() - t0))
        }
        setText(full.slice(0, i))
        if (i >= full.length) {
          window.clearInterval(tick)
        }
      }, 45)
      return () => window.clearInterval(tick)
    }, 600)
    return () => window.clearTimeout(startTimer)
  }, [])

  return (
    <div className="demo-panel demo-terminal">
      <div className="demo-terminal-bar">
        <span />
        <span />
        <span />
        <em>POST /ai-example/chat · SSE</em>
      </div>
      <pre className="demo-terminal-body">
        {started ? (
          <>
            <span className="demo-cursor-line">{text}</span>
            <span className="demo-cursor">▌</span>
          </>
        ) : (
          '等待首 token…'
        )}
      </pre>
      <div className="demo-meta">
        TTFT: <strong>{ttft !== null ? `${ttft} ms` : '—'}</strong>
      </div>
    </div>
  )
}
