import { Alert, Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'

type ResultBodyProps = {
  error: string | null
  /** 次要说明，例如稳定错误码；不当主文案 */
  errorHint?: string | null
  emptyHint: string
  children?: ReactNode
}

/**
 * 结果栏的空态 / 错误 / 内容切换。
 */
export function ResultBody({ error, errorHint, emptyHint, children }: ResultBodyProps) {
  if (error) {
    return (
      <Alert color="red" title="请求失败" variant="light" data-testid="result-error">
        <Text size="sm">{error}</Text>
        {errorHint ? (
          <Text size="xs" c="dimmed" mt={4}>
            {errorHint}
          </Text>
        ) : null}
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
