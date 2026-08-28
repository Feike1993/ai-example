import type { SampleId } from '../../shared/samples'
import { baselineSamples } from '../../shared/samples'
import { scrollToSample } from '../hooks/useScrollSpy'

type StickyNavProps = {
  activeId: SampleId
  variant?: 'sidebar' | 'chips'
}

/** Playground 同款侧栏导航；移动端为 chip bar。 */
export function StickyNav({ activeId, variant = 'sidebar' }: StickyNavProps) {
  if (variant === 'chips') {
    return (
      <nav className="promo-nav-chips" aria-label="样例导航">
        {baselineSamples.map((item) => (
          <button
            key={item.id}
            type="button"
            className={`promo-nav-chip${activeId === item.id ? ' is-active' : ''}`}
            onClick={() => scrollToSample(item.id)}
          >
            {item.label}
          </button>
        ))}
      </nav>
    )
  }

  return (
    <nav className="promo-nav-sidebar" aria-label="样例导航">
      {baselineSamples.map((item) => (
        <button
          key={item.id}
          type="button"
          className={`promo-nav-item${activeId === item.id ? ' is-active' : ''}`}
          onClick={() => scrollToSample(item.id)}
        >
          <span className="promo-nav-label">{item.label}</span>
          <span className="promo-nav-desc">{item.description}</span>
        </button>
      ))}
    </nav>
  )
}
