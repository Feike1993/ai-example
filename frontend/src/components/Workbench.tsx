import { SimpleGrid, Stack, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'

type WorkbenchProps = {
  title: string
  hint: string
  form: ReactNode
  result: ReactNode
  streaming?: boolean
}

/**
 * 输入 | 结果 双栏工作台，各样例面板共用。
 */
export function Workbench({ title, hint, form, result, streaming = false }: WorkbenchProps) {
  return (
    <Stack gap="md" className="workbench">
      <div>
        <Title order={2} fz={28}>
          {title}
        </Title>
        <Text c="dimmed" size="sm" mt={4}>
          {hint}
        </Text>
      </div>
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg" className="workbench-grid">
        <div className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
          {form}
        </div>
        <div
          className={`pane pane-result${streaming ? ' is-streaming' : ''}`}
          style={{ padding: '1.25rem', borderRadius: 6 }}
        >
          {result}
        </div>
      </SimpleGrid>
    </Stack>
  )
}
