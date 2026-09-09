import { Anchor, Button, Stack, Table, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { ApiError, describeError } from '../../api'
import { ResultBody } from '../../components/ResultBody'
import { Workbench } from '../../components/Workbench'
import { getLastTraceId, rememberTraceId } from '../lib/auth'
import { getOpsSnapshot, type OpsSnapshot } from '../lib/productionApi'

const JAEGER_UI = 'http://localhost:16686'

/**
 * 可观测面板：业务指标快照与 Jaeger 深链。
 */
export function ObservabilityPanel() {
  const [snapshot, setSnapshot] = useState<OpsSnapshot | null>(null)
  const [error, setError] = useState<string | null>(null)
  const lastTrace = getLastTraceId()

  const load = async () => {
    setError(null)
    try {
      const data = await getOpsSnapshot()
      setSnapshot(data)
      rememberTraceId(data.traceId)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : describeError(err))
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const traceId = snapshot?.traceId || lastTrace
  const rows: Array<[string, string | number]> = snapshot
    ? [
        ['问答次数', snapshot.chatRuns],
        ['Agent 次数', snapshot.agentRuns],
        ['空检索', snapshot.retrievalEmpty],
        ['护栏拦截', snapshot.guardrailBlocked],
        ['工具拒绝', snapshot.toolDenied],
        ['限流', snapshot.rateLimited],
        ['鉴权失败', snapshot.authFail],
        ['Micrometer meters', snapshot.meters],
      ]
    : []

  return (
    <Workbench
      title="可观测"
      hint="浏览器不直连 Prometheus。这里是登录后可读的安全子集；完整刮取见 /actuator/prometheus（需 JWT）。"
      form={
        <Stack gap="sm">
          <Text size="sm">
            最近 traceId：{traceId ?? '尚无'}
          </Text>
          {traceId ? (
            <Anchor href={`${JAEGER_UI}/trace/${traceId}`} target="_blank" rel="noreferrer">
              在 Jaeger 中打开
            </Anchor>
          ) : (
            <Text size="xs" c="dimmed">
              Compose 下 Jaeger UI：{JAEGER_UI}
            </Text>
          )}
          <Button variant="default" data-testid="refresh-snapshot" onClick={() => void load()}>
            刷新快照
          </Button>
        </Stack>
      }
      result={
        <ResultBody error={error} emptyHint="登录后刷新，这里显示生产链路计数。">
          {rows.length > 0 ? (
            <Table withTableBorder data-testid="ops-snapshot">
              <Table.Tbody>
                {rows.map(([label, value]) => (
                  <Table.Tr key={label}>
                    <Table.Td>{label}</Table.Td>
                    <Table.Td>{value}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          ) : null}
        </ResultBody>
      }
    />
  )
}
