import { motion } from 'motion/react'
import { brand, phaseLabels, type PhaseId } from '../../shared/brand'
import { samples } from '../../shared/samples'

const phaseColor: Record<PhaseId, string> = {
  1: brand.phase1,
  2: brand.phase2,
  3: brand.phase3,
}

/** Hero 区 8 节点阶梯动画，按三期分色。 */
export function HeroSpiral() {
  return (
    <div className="hero-spiral" aria-hidden="true">
      <svg viewBox="0 0 420 320" className="hero-spiral-svg">
        {samples.map((sample, i) => {
          const x = 40 + (i % 4) * 95
          const y = sample.phase === 1 ? 200 : sample.phase === 2 ? 120 : 40
          const color = phaseColor[sample.phase]
          return (
            <motion.g
              key={sample.id}
              initial={{ opacity: 0, scale: 0.6 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: 0.15 + i * 0.08, duration: 0.5 }}
            >
              <motion.circle
                cx={x + 36}
                cy={y + 18}
                r={28}
                fill={sample.phase === 3 ? 'transparent' : color}
                stroke={color}
                strokeWidth={sample.phase === 3 ? 2.5 : 0}
                animate={{ y: [0, -4, 0] }}
                transition={{ repeat: Infinity, duration: 2.4 + i * 0.15, ease: 'easeInOut' }}
              />
              <text x={x + 36} y={y + 23} textAnchor="middle" fill={sample.phase === 3 ? color : brand.white} fontSize="13" fontWeight="700">
                {String(sample.index).padStart(2, '0')}
              </text>
              {i < samples.length - 1 && (
                <line
                  x1={x + 64}
                  y1={y + 18}
                  x2={x + 95}
                  y2={
                    samples[i + 1].phase === sample.phase
                      ? y + 18
                      : samples[i + 1].phase === 2
                        ? 138
                        : 58
                  }
                  stroke="rgba(243,248,245,0.25)"
                  strokeWidth="1.5"
                  strokeDasharray="4 4"
                />
              )}
            </motion.g>
          )
        })}
      </svg>
      <div className="hero-phase-legend">
        {([1, 2, 3] as PhaseId[]).map((p) => (
          <span key={p} style={{ color: phaseColor[p] }}>
            {phaseLabels[p]}
          </span>
        ))}
      </div>
    </div>
  )
}
