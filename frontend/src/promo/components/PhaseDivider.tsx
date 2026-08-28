import { phaseLabels, type PhaseId } from '../../shared/brand'

type PhaseDividerProps = {
  phase: PhaseId
}

/** 三期分章标题条。 */
export function PhaseDivider({ phase }: PhaseDividerProps) {
  return (
    <div className={`phase-divider phase-divider--${phase}`}>
      <span className="phase-divider-num">第 {phase} 期</span>
      <span className="phase-divider-label">{phaseLabels[phase]}</span>
    </div>
  )
}
