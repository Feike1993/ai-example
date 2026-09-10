import { Anchor, Button, Stack, Table, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { ApiError, describeError } from '../../api'
import { ResultBody } from '../../components/ResultBody'
import { Workbench } from '../../components/Workbench'
import { getLastTraceId, rememberTraceId } from '../lib/auth'
import { getLoadtestSummary, getOpsSnapshot, type OpsSnapshot } from '../lib/productionApi'

const JAEGER_UI = 'http://localhost:16686'
const GRAFANA_UI = 'http://localhost:3000'

/**
 * 可观测面板：业务指标快照与 Jaeger 深链。
 */
export function ObservabilityPanel() {
  const [snapshot, setSnapshot] = useState<OpsSnapshot | null>(null)
  const [loadtest, setLoadtest] = useState<string | null>(null)
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
    try {
      const summary = await getLoadtestSummary()
      setLoadtest(formatLoadtest(summary))
    } catch {
      setLoadtest(null)
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
        ['入库投递', snapshot.ingestSubmitted ?? 0],
        ['入库成功', snapshot.ingestSucceeded ?? 0],
        ['最近入库', snapshot.ingestStatus ?? '无'],
        ['Micrometer meters', snapshot.meters],
      ]
    : []

  return (
    <Workbench
      title="可观测"
      hint="浏览器不直连 Prometheus。看板见本机 Grafana；刮取用 PRODUCTION_METRICS_TOKEN，不要匿名放开 actuator。"
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
          <Anchor href={GRAFANA_UI} target="_blank" rel="noreferrer">
            打开 Grafana（默认 admin/admin）
          </Anchor>
          <Text size="sm" data-testid="loadtest-summary">
            最近压测：{loadtest ?? '尚未跑 ./loadtest/run.sh smoke'}
          </Text>
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

function formatLoadtest(summary: Record<string, unknown>): string {
  const metrics = summary.metrics as Record<string, { values?: Record<string, number> }> | undefined
  const duration = metrics?.http_req_duration?.values
  const reqs = metrics?.http_reqs?.values
  const p95 = duration?.['p(95)']
  const count = reqs?.count
  if (p95 == null && count == null) {
    return '已有摘要文件'
  }
  const parts: string[] = []
  if (count != null) {
    parts.push(`${count} reqs`)
  }
  if (p95 != null) {
    parts.push(`p95 ${p95.toFixed(1)}ms`)
  }
  return parts.join(' · ')
}
