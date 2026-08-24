import { SegmentedControl, Stack } from '@mantine/core'
import { useState, type ReactNode } from 'react'
import type { SampleGuideData } from '../guides/types'
import { SampleGuide } from './SampleGuide'

type View = 'play' | 'guide'

type SampleFrameProps = {
  /** 讲解数据 */
  guide: SampleGuideData
  /** 试玩区（通常是 Workbench） */
  children: ReactNode
}

/**
 * 样例外壳：顶部「试玩 | 讲解」切换。
 */
export function SampleFrame({ guide, children }: SampleFrameProps) {
  const [view, setView] = useState<View>('play')

  return (
    <Stack gap="md">
      <SegmentedControl
        value={view}
        onChange={(value) => setView(value as View)}
        data={[
          { value: 'play', label: '试玩' },
          { value: 'guide', label: '讲解' },
        ]}
        w="fit-content"
      />
      {view === 'play' ? children : <SampleGuide guide={guide} />}
    </Stack>
  )
}
