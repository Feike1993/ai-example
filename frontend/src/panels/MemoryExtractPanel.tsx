import { Badge, Button, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type MemoryExtractResponse,
  type MemoryRecallResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { memoryExtractGuide } from '../guides'

/**
 * 进阶：从对话或 session 抽取事实写入长期记忆。
 */
export function MemoryExtractPanel({ provider }: { provider: string }) {
  const [userId, setUserId] = useState('demo')
  const [sessionId, setSessionId] = useState('')
  const [dialogue, setDialogue] = useState(
    'user: 我叫小明，住在杭州，喜欢北京烤鸭\nassistant: 好的，已记下。',
  )
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<MemoryExtractResponse | null>(null)
  const [recall, setRecall] = useState<MemoryRecallResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const parseMessages = () => {
    const messages: { role: string; content: string }[] = []
    for (const line of dialogue.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed) {
        continue
      }
      const idx = trimmed.indexOf(':')
      if (idx < 0) {
        messages.push({ role: 'user', content: trimmed })
        continue
      }
      messages.push({
        role: trimmed.slice(0, idx).trim().toLowerCase() || 'user',
        content: trimmed.slice(idx + 1).trim(),
      })
    }
    return messages
  }

  const runExtract = async () => {
    setError(null)
    setResult(null)
    setRecall(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const messages = parseMessages()
      const body: Record<string, unknown> = {
        userId,
        provider,
        sessionId: sessionId.trim() || undefined,
      }
      if (sessionId.trim() && messages.length === 0) {
        // 仅 sessionId
      } else if (messages.length > 0) {
        body.messages = messages
      } else if (sessionId.trim()) {
        // ok
      } else {
        throw new Error('请填写对话或 sessionId')
      }
      const data = await postJson<MemoryExtractResponse>(`${API_BASE}/memory/extract`, body)
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
      notifications.show({
        color: 'teal',
        title: '抽取完成',
        message: `${data.facts?.length ?? 0} 条事实 · 跳过重复 ${data.skippedDuplicates ?? 0}`,
      })
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'extract 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runRecall = async () => {
    setLoading(true)
    try {
      const data = await postJson<MemoryRecallResponse>(`${API_BASE}/memory/recall`, {
        query: '小明住哪？喜欢什么？',
        userId,
      })
      setRecall(data)
    } catch (err) {
      notifications.show({ color: 'red', title: 'recall 失败', message: describeError(err) })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={memoryExtractGuide}>
      <Workbench
        title="自动抽记忆"
        hint="显式抽取，不静默写会话。可填 sessionId 从上下文会话快照抽取。"
        form={
          <Stack gap="md">
            <TextInput label="userId" value={userId} onChange={(e) => setUserId(e.currentTarget.value)} />
            <TextInput
              label="sessionId（可选；对话为空时必填）"
              value={sessionId}
              onChange={(e) => setSessionId(e.currentTarget.value)}
            />
            <Textarea
              label="对话（role: content 每行一条）"
              minRows={6}
              autosize
              value={dialogue}
              onChange={(e) => setDialogue(e.currentTarget.value)}
            />
            <Button loading={loading} onClick={() => void runExtract()}>
              抽取并写入
            </Button>
            <Button variant="light" loading={loading} disabled={!result} onClick={() => void runRecall()}>
              recall 验证
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="抽取后展示 facts 与 remember 结果。">
            {result && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} />
                <Badge variant="outline">user {result.userId}</Badge>
                <Text size="sm">facts：{(result.facts ?? []).join('；') || '（空）'}</Text>
                <Text size="sm" c="dimmed">
                  skippedDuplicates={result.skippedDuplicates}
                </Text>
                {recall && (
                  <div>
                    <Text fw={600} mb={4}>
                      recall
                    </Text>
                    {recall.empty && <Badge color="orange">empty</Badge>}
                    {(recall.sources ?? []).map((s) => (
                      <Text key={s.id} size="sm" c="dimmed">
                        {s.excerpt}
                      </Text>
                    ))}
                  </div>
                )}
                <RawJsonAccordion value={{ extract: result, recall }} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
