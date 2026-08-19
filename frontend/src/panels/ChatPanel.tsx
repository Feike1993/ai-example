import { Button, NumberInput, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useRef, useState } from 'react'
import { describeError, postJson, streamChat, API_BASE, type ChatResponse } from '../api'
import { JsonBlock } from '../components/JsonBlock'
import { ResultBody } from '../components/ResultBody'
import { Workbench } from '../components/Workbench'

type Mode = 'sync' | 'sse'

/**
 * Chat 样例：同步补全与 SSE，旁注 TTFT。
 */
export function ChatPanel() {
  const [prompt, setPrompt] = useState('用一句话介绍 Token 和上下文窗口')
  const [temperature, setTemperature] = useState<number | string>(0.2)
  const [mode, setMode] = useState<Mode>('sync')
  const [loading, setLoading] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ChatResponse | null>(null)
  const [streamText, setStreamText] = useState('')
  const [ttftMs, setTtftMs] = useState<number | null>(null)
  const stopRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    return () => {
      stopRef.current?.()
    }
  }, [])

  const stopStream = () => {
    stopRef.current?.()
    stopRef.current = null
    setStreaming(false)
    setLoading(false)
  }

  const runSync = async () => {
    setError(null)
    setResult(null)
    setStreamText('')
    setTtftMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const temp = typeof temperature === 'number' ? temperature : undefined
      const body: { prompt: string; temperature?: number } = { prompt }
      if (temp !== undefined) {
        body.temperature = temp
      }
      const data = await postJson<ChatResponse>(`${API_BASE}/chat`, body)
      setTtftMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'Chat 失败', message })
    } finally {
      setLoading(false)
    }
  }

  const runSse = () => {
    setError(null)
    setResult(null)
    setStreamText('')
    setTtftMs(null)
    setLoading(true)
    setStreaming(true)
    const started = performance.now()
    let first = true
    let acc = ''

    stopRef.current = streamChat(
      prompt,
      (chunk) => {
        if (first) {
          first = false
          setTtftMs(Math.round(performance.now() - started))
        }
        acc += chunk
        setStreamText(acc)
      },
      () => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setResult({ content: acc })
      },
      (err) => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setError(err.message)
        notifications.show({ color: 'red', title: 'SSE 失败', message: err.message })
      },
    )
  }

  const onSubmit = () => {
    if (loading && streaming) {
      stopStream()
      return
    }
    if (mode === 'sse') {
      runSse()
      return
    }
    void runSync()
  }

  return (
    <Workbench
      title="Chat"
      hint="同步看完整回复；SSE 观察首 token 时间（TTFT）。"
      streaming={streaming}
      form={
        <Stack gap="md">
          <SegmentedControl
            fullWidth
            value={mode}
            onChange={(value) => setMode(value as Mode)}
            data={[
              { value: 'sync', label: '同步 POST' },
              { value: 'sse', label: 'SSE 流式' },
            ]}
          />
          <Textarea
            label="prompt"
            minRows={8}
            autosize
            value={prompt}
            onChange={(event) => setPrompt(event.currentTarget.value)}
          />
          {mode === 'sync' && (
            <NumberInput
              label="temperature"
              description="留空则用后端默认"
              min={0}
              max={2}
              step={0.1}
              decimalScale={2}
              value={temperature}
              onChange={setTemperature}
            />
          )}
          <Button
            loading={loading && !streaming}
            disabled={!prompt.trim() || (loading && !streaming)}
            onClick={onSubmit}
          >
            {streaming ? '停止' : '发送'}
          </Button>
        </Stack>
      }
      result={
        <ResultBody error={error} emptyHint="发送后，回复和 TTFT 会出现在这里。">
          {(streaming || streamText || result) && (
            <Stack gap="sm">
              {ttftMs !== null && (
                <Text size="sm" c="dimmed">
                  {mode === 'sse' ? 'TTFT' : '耗时'} {ttftMs} ms
                </Text>
              )}
              {streaming ? (
                <pre className="stream-text">
                  {streamText}
                  <span className="sse-caret" />
                </pre>
              ) : (
                result && <JsonBlock value={result} />
              )}
            </Stack>
          )}
        </ResultBody>
      }
    />
  )
}
