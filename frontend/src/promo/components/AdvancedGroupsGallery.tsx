import { motion } from 'motion/react'
import { advancedNavGroups, type AdvancedNavGroupId } from '../../shared/samples'

const IMAGE_SRC: Record<AdvancedNavGroupId, string> = {
  rag: '/promo/advanced/rag.png',
  memory: '/promo/advanced/memory.png',
  mcp: '/promo/advanced/mcp.png',
  agentObs: '/promo/advanced/agent-obs.png',
  quality: '/promo/advanced/quality.png',
}

/** 每组一句底层逻辑（宣传图旁说明）。 */
const LOGIC_BLURB: Record<AdvancedNavGroupId, string> = {
  rag: '问句经改写 / HyDE / memory_rewrite 后再检索；memoryHints 永不混入 RAG sources。',
  memory: 'remember / extract 写入独立 corpus，再按 topK / 阈值 recall，与会话 trim 窗口分离。',
  mcp: 'remote 链路校验 Authorization Bearer；inprocess 同进程免鉴权对照。',
  agentObs: 'ReAct 多跳推送 tool_call / tool_result SSE，旁路累加 TokenUsage。',
  quality: 'input/output deny 与结构校验短路；旁路 golden eval 看通过率。',
}

/**
 * 进阶五组静态逻辑宣传图墙（与基础 Journey 分区展示）。
 * StickyNav 仍只导航基础 8 样例；本节用锚点 advanced-groups。
 */
export function AdvancedGroupsGallery() {
  return (
    <section id="advanced-groups" className="advanced-groups" aria-labelledby="advanced-groups-title">
      <header className="advanced-groups-header">
        <span className="promo-stage-badge promo-stage-badge--on-dark">进阶</span>
        <p className="advanced-groups-eyebrow">第四～十三期 · 主题分组</p>
        <h2 id="advanced-groups-title" className="advanced-groups-title">
          五组逻辑
        </h2>
        <p className="advanced-groups-lead">
          与上方基础 8 样例动效不同：这里用静态图概括检索 / 记忆 / MCP / 可观测 / 护栏的底层链路；细节进 Playground 进阶区。
        </p>
      </header>
      <div className="advanced-groups-grid">
        {advancedNavGroups.map((group, i) => (
          <motion.article
            key={group.id}
            className="advanced-group-item"
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: '-8% 0px' }}
            transition={{ duration: 0.4, delay: i * 0.04 }}
          >
            <h3 className="advanced-group-label">{group.label}</h3>
            <p className="advanced-group-blurb">{LOGIC_BLURB[group.id]}</p>
            <img
              className="advanced-group-img"
              src={IMAGE_SRC[group.id]}
              alt={`${group.label} 底层逻辑示意`}
              loading="lazy"
              width={1600}
              height={900}
            />
          </motion.article>
        ))}
      </div>
      <p className="advanced-groups-cta">
        <a href="/index.html">在 Playground 进阶区试用 →</a>
      </p>
    </section>
  )
}
