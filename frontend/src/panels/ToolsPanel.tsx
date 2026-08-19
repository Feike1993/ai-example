import { Button, Stack, Textarea } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { describeError, postJson, API_BASE, type ToolChatResponse } from '../api'
import { JsonBlock } from '../components/JsonBlock'
import { ResultBody } from '../components/ResultBody'
import { Workbench } from '../components/Workbench'

/**
 * Tool Calling 样例：模型可调天气与计算器演示工具。
 */
export function ToolsPanel() {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ToolChatResponse | null>(null)

  const run = async () => {
    setError(null)
    setResult(null)
    setLoading(true)
    try {
      const data = await postJson<ToolChatResponse>(`${API_BASE}/tools`, { prompt })
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
          {result && <JsonBlock value={result} />}
        </ResultBody>
      }
    />
  )
}
