import { Alert, Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'

type ResultBodyProps = {
  error: string | null
  emptyHint: string
  children?: ReactNode
}

/**
 * 结果栏的空态 / 错误 / 内容切换。
 */
export function ResultBody({ error, emptyHint, children }: ResultBodyProps) {
  if (error) {
    return (
      <Alert color="red" title="请求失败" variant="light">
        {error}
      </Alert>
    )
  }
  if (!children) {
    return (
      <Stack gap={6}>
        <Text size="sm" c="dimmed">
          {emptyHint}
        </Text>
      </Stack>
    )
  }
  return <>{children}</>
}
