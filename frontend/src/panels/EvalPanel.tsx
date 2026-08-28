import { Badge, Button, Stack, Table, Text } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { API_BASE, describeError, formatTokenUsage, postJson, type EvalRunResponse } from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { evalGuide } from '../guides'

/**
 * Agent 评测：一键跑 classpath golden suite。
 */
export function EvalPanel({ provider }: { provider: string }) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<EvalRunResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const runSuite = async () => {
    setError(null)
    setResult(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<EvalRunResponse>(`${API_BASE}/eval/run`, { provider })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
      notifications.show({
        color: data.failed === 0 ? 'teal' : 'yellow',
        title: '评测完成',
        message: `${data.passed}/${data.total} 通过`,
      })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '评测失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={evalGuide}>
      <Workbench
        title="Agent 评测"
        hint="跑 classpath eval/golden/*.json；RAG 用例需先 ingest。会真实调用 LLM。"
        form={
          <Button loading={loading} onClick={() => void runSuite()}>
            运行 golden suite
          </Button>
        }
        result={
          <ResultBody error={error} emptyHint="点击运行后，通过率与明细会出现在这里。">
            {result && (
              <Stack gap="md">
                <RequestMeta elapsedMs={elapsedMs} usage={result.usageSummary} />
                <GroupSummary total={result.total} passed={result.passed} failed={result.failed} />
                {formatTokenUsage(result.usageSummary) && (
                  <Text size="sm" c="dimmed">
                    用量汇总：{formatTokenUsage(result.usageSummary)}
                  </Text>
                )}
                <Table striped highlightOnHover withTableBorder>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>id</Table.Th>
                      <Table.Th>结果</Table.Th>
                      <Table.Th>耗时</Table.Th>
                      <Table.Th>steps</Table.Th>
                      <Table.Th>toolFailures</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {result.cases.map((item) => (
                      <Table.Tr
                        key={item.id}
                        style={{ cursor: 'pointer' }}
                        onClick={() => setExpandedId(expandedId === item.id ? null : item.id)}
                      >
                        <Table.Td>{item.id}</Table.Td>
                        <Table.Td>
                          <Badge color={item.passed ? 'teal' : 'red'} variant="light">
                            {item.passed ? 'PASS' : 'FAIL'}
                          </Badge>
                        </Table.Td>
                        <Table.Td>{item.durationMs} ms</Table.Td>
                        <Table.Td>{item.steps}</Table.Td>
                        <Table.Td>{item.toolFailures}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
                {expandedId && (
                  <div>
                    {result.cases
                      .filter((item) => item.id === expandedId)
                      .map((item) => (
                        <Stack key={item.id} gap={4}>
                          <Text size="sm" fw={600}>
                            {item.id}
                          </Text>
                          {item.error && (
                            <Text size="sm" c="red">
                              {item.error}
                            </Text>
                          )}
                          {item.answer && <pre className="stream-text">{item.answer}</pre>}
                        </Stack>
                      ))}
                  </div>
                )}
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}

function GroupSummary({ total, passed, failed }: { total: number; passed: number; failed: number }) {
  return (
    <Text>
      通过率：{passed}/{total}（失败 {failed}）
    </Text>
  )
}
