import { Button, Stack, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { describeError, postJson, API_BASE, type ToolChatResponse } from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { toolsGuide } from '../guides'

/**
 * Tool Calling 样例：模型可调天气与计算器演示工具。
 */
export function ToolsPanel({ provider }: { provider: string }) {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ToolChatResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<ToolChatResponse>(`${API_BASE}/tools`, { prompt, provider })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'Tools 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={toolsGuide}>
      <Workbench
        title="Tool Calling"
        hint="POST /ai-example/tools，模型按需调用演示工具后再回答。"
        form={
          <Stack gap="md">
            <Textarea
              label="prompt"
              minRows={10}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Button loading={loading} disabled={!prompt.trim() || loading} onClick={() => void run()}>
              调用
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="调用后展示模型结合工具结果后的回复。">
            {result && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={result.usage} />
                <pre className="stream-text">{result.content}</pre>
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
