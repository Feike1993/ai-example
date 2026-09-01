import { Badge, Button, Group, SegmentedControl, Stack, Text, TextInput, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type ContextChatResponse,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { contextGuide } from '../guides'

type Strategy = 'trim' | 'summarize'

/**
 * 上下文工程：多轮会话 + trim/summarize。
 */
export function ContextPanel({ provider }: { provider: string }) {
  const [sessionId, setSessionId] = useState(() => `demo-${Date.now().toString(36)}`)
  const [prompt, setPrompt] = useState('我叫小明，喜欢北京')
  const [strategy, setStrategy] = useState<Strategy>('trim')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ContextChatResponse | null>(null)
  const [history, setHistory] = useState<{ role: string; content: string }[]>([])
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<ContextChatResponse>(`${API_BASE}/context/chat`, {
        sessionId,
        prompt,
        provider,
        strategy,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
      setSessionId(data.sessionId)
      setHistory((prev) => [
        ...prev,
        { role: 'user', content: prompt },
        { role: 'assistant', content: data.content },
      ])
      setPrompt('我叫什么？喜欢哪个城市？')
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '上下文聊天失败', message })
    } finally {
      setLoading(false)
    }
  }

  const clearSession = async () => {
    try {
      await fetch(`${API_BASE}/context/session/${encodeURIComponent(sessionId)}`, {
        method: 'DELETE',
      })
      setHistory([])
      setResult(null)
      setElapsedMs(null)
      notifications.show({ color: 'teal', title: '已清空会话', message: sessionId })
    } catch (err) {
      notifications.show({ color: 'red', title: '清空失败', message: describeError(err) })
    }
  }

  return (
    <SampleFrame guide={contextGuide}>
      <Workbench
        title="上下文工程"
        hint="同一 sessionId 多轮连聊；默认 store=jdbc 时重启 Java 仍可续聊（需 Postgres）。"
        form={
          <Stack gap="md">
            <TextInput
              label="sessionId"
              description="留空后端会新建；刷新页面会生成新 demo id"
              value={sessionId}
              onChange={(event) => setSessionId(event.currentTarget.value)}
            />
            <SegmentedControl
              fullWidth
              value={strategy}
              onChange={(value) => setStrategy(value as Strategy)}
              data={[
                { value: 'trim', label: 'trim' },
                { value: 'summarize', label: 'summarize' },
              ]}
            />
            <Textarea
              label="prompt"
              minRows={6}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Group>
              <Button loading={loading} disabled={!prompt.trim() || loading} onClick={() => void run()}>
                发送
              </Button>
              <Button variant="light" onClick={() => void clearSession()}>
                清空会话
              </Button>
            </Group>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="发送后展示回复与预算元数据；左侧可继续多轮。">
            {(result || history.length > 0) && (
              <Stack gap="sm">
                {history.length > 0 && (
                  <Stack gap={6}>
                    <Text size="sm" c="dimmed">
                      本页对话（前端缓存）
                    </Text>
                    {history.map((item, index) => (
                      <Text key={`${item.role}-${index}`} size="sm">
                        <Badge size="xs" mr={6} variant="light">
                          {item.role}
                        </Badge>
                        {item.content}
                      </Text>
                    ))}
                  </Stack>
                )}
                {result && (
                  <>
                    <RequestMeta elapsedMs={elapsedMs} usage={result.usage} />
                    <Group gap="xs">
                      <Badge variant="light">{result.strategy}</Badge>
                      {result.store && (
                        <Badge color={result.store === 'jdbc' ? 'teal' : 'gray'} variant="light">
                          store {result.store}
                        </Badge>
                      )}
                      <Badge variant="outline">raw {result.rawMessageCount}</Badge>
                      <Badge variant="outline">sent {result.sentMessageCount}</Badge>
                      <Badge variant="outline">~{result.approxTokens} tok</Badge>
                      {result.droppedCount > 0 && (
                        <Badge color="orange" variant="light">
                          dropped {result.droppedCount}
                        </Badge>
                      )}
                    </Group>
                    {result.summary && (
                      <Text size="sm" c="dimmed">
                        summary: {result.summary}
                      </Text>
                    )}
                    <MarkdownBody>{result.content}</MarkdownBody>
                    <RawJsonAccordion value={result} />
                  </>
                )}
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
