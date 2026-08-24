import { CodeHighlight } from '@mantine/code-highlight'
import { List, SegmentedControl, Stack, Text, Title } from '@mantine/core'
import { useState } from 'react'
import type { SampleGuideData } from '../guides/types'

type Side = 'backend' | 'frontend'

/**
 * 样例讲解页：概念要点 + 后端/前端核心代码切换。
 */
export function SampleGuide({ guide }: { guide: SampleGuideData }) {
  const [side, setSide] = useState<Side>('backend')
  const snippets = side === 'backend' ? guide.backend : guide.frontend

  return (
    <Stack gap="md" className="workbench">
      <div>
        <Title order={2} fz={28}>
          {guide.title}
        </Title>
        <Text c="dimmed" size="sm" mt={4}>
          核心概念与源码对照
        </Text>
      </div>

      <div className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
        <Text fw={600} mb="sm">
          概念要点
        </Text>
        <List size="sm" spacing="xs">
          {guide.concepts.map((item) => (
            <List.Item key={item}>{item}</List.Item>
          ))}
        </List>
      </div>

      <SegmentedControl
        value={side}
        onChange={(value) => setSide(value as Side)}
        data={[
          { value: 'backend', label: '后端' },
          { value: 'frontend', label: '前端' },
        ]}
        w="fit-content"
      />

      <Stack gap="lg">
        {snippets.map((snippet) => (
          <div key={snippet.label} className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
            <Text fw={600} mb="sm" size="sm">
              {snippet.label}
            </Text>
            <CodeHighlight
              code={snippet.code}
              language={snippet.language === 'tsx' ? 'typescript' : 'java'}
              radius="md"
              copyLabel="复制"
              copiedLabel="已复制"
            />
          </div>
        ))}
      </Stack>
    </Stack>
  )
}
