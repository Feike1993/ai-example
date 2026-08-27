import { Badge, Button, DataList, Group, Stack, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type Ticket,
  type TicketResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { structuredGuide } from '../guides'

function priorityColor(priority: string): string {
  const key = priority.toUpperCase()
  if (key === 'P0') return 'red'
  if (key === 'P1') return 'orange'
  if (key === 'P2') return 'yellow'
  return 'seaweed'
}

/**
 * 结构化输出样例：自然语言抽工单。
 */
export function StructuredPanel({ provider }: { provider: string }) {
  const [text, setText] = useState('登录页偶尔 500，P1，标签 backend,auth')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [ticket, setTicket] = useState<Ticket | null>(null)
  const [rawResponse, setRawResponse] = useState<TicketResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setTicket(null)
    setRawResponse(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<TicketResponse>(`${API_BASE}/structured/ticket`, { text, provider })
      setElapsedMs(Math.round(performance.now() - started))
      setRawResponse(data)
      setTicket(data.ticket)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '抽取失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={structuredGuide}>
      <Workbench
        title="结构化输出"
        hint="把自然语言抽成 Ticket JSON（title / priority / labels / summary）。"
        form={
          <Stack gap="md">
            <Textarea
              label="text"
              minRows={10}
              autosize
              value={text}
              onChange={(event) => setText(event.currentTarget.value)}
            />
            <Button loading={loading} disabled={!text.trim() || loading} onClick={() => void run()}>
              抽取工单
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="抽取后会先看到字段，再可展开原始 JSON。">
            {ticket && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} usage={rawResponse?.usage} />
                <DataList orientation="vertical" withDivider>
                  <DataList.Item>
                    <DataList.ItemLabel>title</DataList.ItemLabel>
                    <DataList.ItemValue>{ticket.title}</DataList.ItemValue>
                  </DataList.Item>
                  <DataList.Item>
                    <DataList.ItemLabel>priority</DataList.ItemLabel>
                    <DataList.ItemValue>
                      <Badge color={priorityColor(ticket.priority)} variant="light">
                        {ticket.priority}
                      </Badge>
                    </DataList.ItemValue>
                  </DataList.Item>
                  <DataList.Item>
                    <DataList.ItemLabel>labels</DataList.ItemLabel>
                    <DataList.ItemValue>
                      <Group gap={6}>
                        {ticket.labels.map((label) => (
                          <Badge key={label} variant="outline" color="seaweed">
                            {label}
                          </Badge>
                        ))}
                      </Group>
                    </DataList.ItemValue>
                  </DataList.Item>
                  <DataList.Item>
                    <DataList.ItemLabel>summary</DataList.ItemLabel>
                    <DataList.ItemValue>{ticket.summary}</DataList.ItemValue>
                  </DataList.Item>
                </DataList>
                <RawJsonAccordion value={rawResponse ?? ticket} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
