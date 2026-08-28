import { brand } from '../shared/brand'
import { buildSpiralPath, getSpiralNodePositions, SPIRAL_VIEWBOX } from './spiralNodes'

const phaseColor = { 1: brand.phase1, 2: brand.phase2, 3: brand.phase3 } as const

const chips = [
  { title: '8 样例', desc: '闭环' },
  { title: '全栈', desc: '可跑' },
  { title: '即学', desc: '即用' },
] as const

/** 1080×1080 方版「免费 · 开源」宣传图。 */
export function OpenSourcePoster() {
  const nodes = getSpiralNodePositions()
  const pathD = buildSpiralPath(nodes)

  return (
    <div className="poster-canvas" style={{ background: brand.heroDark }}>
      <div className="poster-glow" aria-hidden="true" />
      <div className="poster-inner">
        <header className="poster-header">
          <div className="poster-wordmark">
            <span className="poster-wordmark-ai">AI</span>
            <span>Example</span>
          </div>
          <span className="poster-version">v0.2.0 baseline</span>
        </header>

        <div className="poster-spiral-wrap" aria-hidden="true">
          <svg
            className="poster-spiral-svg"
            viewBox={`0 0 ${SPIRAL_VIEWBOX.width} ${SPIRAL_VIEWBOX.height}`}
          >
            <path
              d={pathD}
              fill="none"
              stroke="rgba(243,248,245,0.3)"
              strokeWidth={2.5}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            {nodes.map((node) => {
              const color = phaseColor[node.phase]
              const isHollow = node.phase === 3
              return (
                <g key={node.index}>
                  <circle
                    cx={node.cx}
                    cy={node.cy}
                    r={28}
                    fill={isHollow ? 'transparent' : color}
                    stroke={color}
                    strokeWidth={isHollow ? 2.5 : 0}
                  />
                  <text
                    x={node.cx}
                    y={node.cy + 5}
                    textAnchor="middle"
                    fill={isHollow ? color : brand.white}
                    fontSize={13}
                    fontWeight={700}
                    fontFamily="Syne, Noto Sans SC, sans-serif"
                  >
                    {String(node.index).padStart(2, '0')}
                  </text>
                </g>
              )
            })}
          </svg>
        </div>

        <h1 className="poster-title">免费 · 开源</h1>
        <p className="poster-subtitle">Agent 学习 Cookbook</p>

        <div className="poster-chips">
          {chips.map((chip) => (
            <div key={chip.title} className="poster-chip">
              <strong>{chip.title}</strong>
              <span>{chip.desc}</span>
            </div>
          ))}
        </div>

        <footer className="poster-footer">
          <p className="poster-repo">github.com/Feike1993/ai-example</p>
          <p className="poster-tagline">免费开源 · Clone 即学 · 无需付费</p>
        </footer>
      </div>
    </div>
  )
}
