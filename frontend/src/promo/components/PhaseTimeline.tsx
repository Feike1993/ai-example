import { phaseLabels, type PhaseId } from '../../shared/brand'
import { samples } from '../../shared/samples'

const phases: PhaseId[] = [1, 2, 3]

/** 三期关系横向 timeline。 */
export function PhaseTimeline() {
  return (
    <section className="phase-timeline">
      <h2>三期递进</h2>
      <p className="phase-timeline-sub">每一期在前一期基础上叠加，而非替换。</p>
      <div className="phase-timeline-track">
        {phases.map((phase, i) => (
          <div key={phase} className="phase-timeline-card">
            <span className="phase-timeline-num">第 {phase} 期</span>
            <h3>{phaseLabels[phase].split(' · ')[1]}</h3>
            <ul>
              {samples
                .filter((s) => s.phase === phase)
                .map((s) => (
                  <li key={s.id}>
                    {String(s.index).padStart(2, '0')} {s.label}
                  </li>
                ))}
            </ul>
            {i < phases.length - 1 && <span className="phase-timeline-arrow">在此之上叠加 →</span>}
          </div>
        ))}
      </div>
    </section>
  )
}
