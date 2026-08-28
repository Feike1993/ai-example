import { motion } from 'motion/react'

/** 多 Agent Orchestrator 分叉汇总。 */
export function MultiAgentOrchestra() {
  return (
    <div className="demo-panel demo-multiagent">
      <div className="demo-ma-orchestrator">
        <strong>Orchestrator</strong>
        <span>拆任务 · 选专员</span>
      </div>
      <div className="demo-ma-branches">
        <motion.div className="demo-ma-worker" animate={{ y: [0, -3, 0] }} transition={{ repeat: Infinity, duration: 2 }}>
          <strong>WorkerA</strong>
          <span>工具 · 查天气</span>
        </motion.div>
        <motion.div
          className="demo-ma-worker"
          animate={{ y: [0, -3, 0] }}
          transition={{ repeat: Infinity, duration: 2, delay: 0.4 }}
        >
          <strong>WorkerB</strong>
          <span>执笔 · 写建议</span>
        </motion.div>
      </div>
      <motion.div
        className="demo-ma-result"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.8, repeat: Infinity, repeatDelay: 2.5, duration: 0.5 }}
      >
        汇总：北京 22°C 晴，建议带防晒出行
      </motion.div>
    </div>
  )
}
