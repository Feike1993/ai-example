import { useEffect, useState } from 'react'

const messages = ['用户: 我叫小明', '助手: 你好小明', '用户: 喜欢北京', '助手: 记住了', '用户: 刚才叫什么？']

/** 上下文 trim / summarize 预算演示。 */
export function ContextBudget() {
  const [mode, setMode] = useState<'trim' | 'summarize'>('trim')
  const [visible, setVisible] = useState(messages.length)

  useEffect(() => {
    const t = window.setInterval(() => {
      setMode((m) => {
        const next = m === 'trim' ? 'summarize' : 'trim'
        setVisible(next === 'trim' ? 3 : messages.length)
        return next
      })
    }, 2800)
    return () => window.clearInterval(t)
  }, [])

  return (
    <div className="demo-panel demo-context">
      <div className="demo-context-tabs">
        <button type="button" className={mode === 'trim' ? 'is-active' : ''} onClick={() => setMode('trim')}>
          trim
        </button>
        <button type="button" className={mode === 'summarize' ? 'is-active' : ''} onClick={() => setMode('summarize')}>
          summarize
        </button>
      </div>
      <div className="demo-context-msgs">
        {mode === 'summarize' && <div className="demo-context-summary">摘要: 用户叫小明，喜欢北京</div>}
        {messages.slice(mode === 'trim' ? messages.length - visible : 0).map((m, i) => (
          <div key={i} className="demo-context-msg">
            {m}
          </div>
        ))}
        {mode === 'trim' && visible < messages.length && (
          <div className="demo-context-trimmed">… 已裁剪 {messages.length - visible} 条旧消息</div>
        )}
      </div>
    </div>
  )
}
