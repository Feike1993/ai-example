import { Badge, Button, NumberInput, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type MemoryChatCompareResponse,
  type MemoryRecallCompareResponse,
  type MemoryRecallResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { memoryCompareGuide } from '../guides'

/**
 * 进阶：召回 topK/阈值与有无记忆 chat 对照。
 */
export function MemoryComparePanel({ provider }: { provider: string }) {
  const [userId, setUserId] = useState('demo')
  const [query, setQuery] = useState('根据记忆，我喜欢吃什么？')
  const [lowTopK, setLowTopK] = useState(1)
  const [highTopK, setHighTopK] = useState(8)
  const [threshold, setThreshold] = useState(0.5)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recallCompare, setRecallCompare] = useState<MemoryRecallCompareResponse | null>(null)
  const [chatCompare, setChatCompare] = useState<MemoryChatCompareResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const runRecallCompare = async () => {
    setError(null)
    setChatCompare(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<MemoryRecallCompareResponse>(`${API_BASE}/memory/recall/compare`, {
        query,
        userId,
        lowTopK,
        highTopK,
        similarityThreshold: threshold,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setRecallCompare(data)
      notifications.show({ color: 'teal', title: 'recall/compare 完成', message: '三路 sources 已返回' })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'recall/compare 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runChatCompare = async () => {
    setError(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<MemoryChatCompareResponse>(`${API_BASE}/memory/chat/compare`, {
        prompt: query,
        userId,
        provider,
        topK: highTopK,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setChatCompare(data)
      notifications.show({ color: 'teal', title: 'chat/compare 完成', message: '有无记忆答案已返回' })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'chat/compare 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const renderBranch = (title: string, branch: MemoryRecallResponse | undefined) => (
    <div>
      <Text fw={600} size="sm" mb={4}>
        {title}
      </Text>
      {branch?.empty && <Badge color="orange">empty</Badge>}
      {branch?.note && (
        <Text size="xs" c="dimmed">
          {branch.note}
        </Text>
      )}
      {(branch?.sources ?? []).map((s) => (
        <Text key={s.id} size="sm" c="dimmed">
          {s.excerpt}
        </Text>
      ))}
      {!branch?.empty && (branch?.sources?.length ?? 0) === 0 && (
        <Text size="sm" c="dimmed">
          （无 sources）
        </Text>
      )}
    </div>
  )

  return (
    <SampleFrame guide={memoryCompareGuide}>
      <Workbench
        title="召回策略对照"
        hint="先 remember / extract 写入事实，再对照三路召回与有无记忆答案。"
        form={
          <Stack gap="md">
            <TextInput label="userId" value={userId} onChange={(e) => setUserId(e.currentTarget.value)} />
            <Textarea
              label="问题 / prompt"
              minRows={3}
              autosize
              value={query}
              onChange={(e) => setQuery(e.currentTarget.value)}
            />
            <NumberInput label="lowTopK" value={lowTopK} min={1} onChange={(v) => setLowTopK(Number(v) || 1)} />
            <NumberInput label="highTopK" value={highTopK} min={1} onChange={(v) => setHighTopK(Number(v) || 8)} />
            <NumberInput
              label="similarityThreshold"
              value={threshold}
              min={0}
              max={1}
              step={0.05}
              decimalScale={2}
              onChange={(v) => setThreshold(typeof v === 'number' ? v : 0.5)}
            />
            <Button loading={loading} onClick={() => void runRecallCompare()}>
              recall/compare
            </Button>
            <Button variant="light" loading={loading} onClick={() => void runChatCompare()}>
              chat/compare（有无记忆）
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="运行对照后展示三路 sources 或两套答案。">
            {(recallCompare || chatCompare) && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} />
                {recallCompare && (
                  <Stack gap="sm">
                    <Badge variant="outline">
                      low={recallCompare.lowTopKSize} · high={recallCompare.highTopKSize} · thr=
                      {recallCompare.similarityThreshold}
                    </Badge>
                    {renderBranch('lowTopK', recallCompare.lowTopK)}
                    {renderBranch('highTopK', recallCompare.highTopK)}
                    {renderBranch('withThreshold', recallCompare.withThreshold)}
                  </Stack>
                )}
                {chatCompare && (
                  <Stack gap="sm">
                    <div>
                      <Text fw={600} mb={4}>
                        withMemory
                      </Text>
                      <Text size="sm">{chatCompare.withMemory?.answer}</Text>
                    </div>
                    <div>
                      <Text fw={600} mb={4}>
                        withoutMemory
                      </Text>
                      <Text size="sm">{chatCompare.withoutMemory?.answer}</Text>
                    </div>
                  </Stack>
                )}
                <RawJsonAccordion value={{ recallCompare, chatCompare }} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
