import { Badge, Button, Checkbox, Code, Stack, Text, Textarea, Timeline } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  API_BASE,
  describeError,
  postJson,
  type GuardrailChatResponse,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { guardrailGuide } from '../guides'
import type { SampleGuideData } from '../guides'

/**
 * 输出护栏样例：展示逐步 checks 与 blocked 态。
 */
export function GuardrailPanel({
  provider,
  guide = guardrailGuide,
  title = '输出护栏',
}: {
  provider: string
  guide?: SampleGuideData
  title?: string
}) {
  const [prompt, setPrompt] = useState('用一句话介绍输出护栏')
  const [requireStructured, setRequireStructured] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<GuardrailChatResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<GuardrailChatResponse>(`${API_BASE}/guardrail/chat`, {
        prompt,
        provider,
        requireStructured,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '护栏请求失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={guide}>
      <Workbench
        title={title}
        hint="默认词表含「违禁演示词」；输入命中则不调模型。可开结构信封。"
        form={
          <Stack gap="md">
            <Textarea
              label="prompt"
              minRows={6}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Checkbox
              label="requireStructured（SafeEnvelope）"
              checked={requireStructured}
              onChange={(event) => setRequireStructured(event.currentTarget.checked)}
            />
            <Button loading={loading} disabled={!prompt.trim()} onClick={() => void run()}>
              发送
            </Button>
          </Stack>
        }
        result={
          <Stack gap="md">
            <RequestMeta elapsedMs={elapsedMs} usage={result?.usage} error={error} />
            {result && (
              <>
                <Badge color={result.blocked ? 'red' : 'teal'} variant="light">
                  {result.blocked ? `blocked @ ${result.blockStage}` : 'passed'}
                </Badge>
                <Timeline active={result.checks.length - 1} bulletSize={18} lineWidth={2}>
                  {result.checks.map((check) => (
                    <Timeline.Item
                      key={`${check.name}-${check.detail}`}
                      title={check.name}
                      color={check.passed ? 'teal' : 'red'}
                    >
                      <Text size="sm" c="dimmed">
                        {check.passed ? '通过' : '失败'} · {check.detail}
                      </Text>
                    </Timeline.Item>
                  ))}
                </Timeline>
                <ResultBody>
                  <MarkdownBody>{result.answer}</MarkdownBody>
                </ResultBody>
                <RawJsonAccordion value={result} />
              </>
            )}
            {!result && !error && (
              <Text size="sm" c="dimmed">
                试正常问题，或把 prompt 改成含「违禁演示词」看 input_deny。
              </Text>
            )}
            {error && <Code block>{error}</Code>}
          </Stack>
        }
      />
    </SampleFrame>
  )
}
