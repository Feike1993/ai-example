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
 * <p>
 * 有错误时仍渲染 children：生产多轮对话在失败时必须能看见历史和半截答案。
 */
export function ResultBody({ error, errorHint, emptyHint, children }: ResultBodyProps) {
  return (
    <Stack gap={6}>
      {error ? (
        <Alert color="red" title="请求失败" variant="light" data-testid="result-error">
          <Text size="sm">{error}</Text>
          {errorHint ? (
            <Text size="xs" c="dimmed" mt={4}>
              {errorHint}
            </Text>
          ) : null}
        </Alert>
      ) : null}
      {!error && !children ? (
        <Text size="sm" c="dimmed">
          {emptyHint}
        </Text>
      ) : null}
      {children}
    </Stack>
  )
}
