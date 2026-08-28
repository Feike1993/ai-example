import { useEffect, useState } from 'react'
import type { SampleId } from '../../shared/samples'

/** 根据 scroll 位置切换侧栏 active 项。 */
export function useScrollSpy(ids: SampleId[]) {
  const [activeId, setActiveId] = useState<SampleId>(ids[0] ?? 'chat')

  useEffect(() => {
    const onScroll = () => {
      let best: SampleId = ids[0]
      let bestDist = Number.POSITIVE_INFINITY
      const anchor = window.innerHeight * 0.35

      for (const id of ids) {
        const el = document.getElementById(id)
        if (!el) {
          continue
        }
        const dist = Math.abs(el.getBoundingClientRect().top - anchor)
        if (dist < bestDist) {
          bestDist = dist
          best = id
        }
      }
      setActiveId(best)
    }

    window.addEventListener('scroll', onScroll, { passive: true })
    onScroll()
    return () => window.removeEventListener('scroll', onScroll)
  }, [ids])

  return { activeId, setActiveId }
}

/** 点击侧栏时平滑滚动并更新 hash。 */
export function scrollToSample(id: SampleId) {
  const el = document.getElementById(id)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    history.replaceState(null, '', `#${id}`)
  }
}

/** 页面加载时根据 hash 定位到对应样例。 */
export function useHashNavigation(setActiveId: (id: SampleId) => void) {
  useEffect(() => {
    const hash = window.location.hash.replace('#', '') as SampleId
    if (hash) {
      requestAnimationFrame(() => {
        scrollToSample(hash)
        setActiveId(hash)
      })
    }
  }, [setActiveId])
}
