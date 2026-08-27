import { Stack, Text } from '@mantine/core'
import { formatTokenUsage, type TokenUsage } from '../api'

type RequestMetaProps = {
  /** 客户端测得的耗时（ms）；SSE 首包可用 TTFT 标签 */
  elapsedMs?: number | null
  /** 标签：同步用「耗时」，SSE 首包用「TTFT」 */
  elapsedLabel?: string
  usage?: TokenUsage | null
}

/**
 * 展示请求耗时与 token 用量（有数据才渲染）。
 */
export function RequestMeta({
  elapsedMs = null,
  elapsedLabel = '耗时',
  usage = null,
}: RequestMetaProps) {
  const usageText = formatTokenUsage(usage)
  if (elapsedMs == null && !usageText) {
    return null
  }
  return (
    <Stack gap={2}>
      {elapsedMs != null && (
        <Text size="sm" c="dimmed">
          {elapsedLabel} {elapsedMs} ms
        </Text>
      )}
      {usageText && (
        <Text size="sm" c="dimmed">
          Token {usageText}
        </Text>
      )}
    </Stack>
  )
}
