import { Badge, Button, Code, NumberInput, SegmentedControl, Stack, Text, Textarea, Timeline } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type AgentTrace,
  type FrameworkResponse,
} from '../api'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { agentGuide } from '../guides'

type Mode = 'react' | 'framework'

/**
 * Agent 样例：显式 ReAct Loop 与框架托管 tool-calling 对照。
 */
export function AgentPanel({ provider }: { provider: string }) {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [maxSteps, setMaxSteps] = useState<number | string>(8)
  const [mode, setMode] = useState<Mode>('react')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [trace, setTrace] = useState<AgentTrace | null>(null)
  const [framework, setFramework] = useState<FrameworkResponse | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setTrace(null)
    setFramework(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const body: { prompt: string; maxSteps?: number; provider: string } = { prompt, provider }
      if (mode === 'react' && typeof maxSteps === 'number') {
        body.maxSteps = maxSteps
      }
      if (mode === 'react') {
        const data = await postJson<AgentTrace>(`${API_BASE}/agent/react`, body)
        setElapsedMs(Math.round(performance.now() - started))
        setTrace(data)
      } else {
        const data = await postJson<FrameworkResponse>(`${API_BASE}/agent/framework`, body)
        setElapsedMs(Math.round(performance.now() - started))
        setFramework(data)
      }
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: 'Agent 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={agentGuide}>
      <Workbench
        title="Agent Loop"
        hint="ReAct 带工具轨迹；Framework 是 Spring AI 自动执行 tool_calls。"
        form={
          <Stack gap="md">
            <SegmentedControl
              fullWidth
              value={mode}
              onChange={(value) => setMode(value as Mode)}
              data={[
                { value: 'react', label: 'ReAct' },
                { value: 'framework', label: 'Framework' },
              ]}
            />
            <Textarea
              label="prompt"
              minRows={8}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <NumberInput
              label="maxSteps"
              description="仅显式 Loop 使用；触达上限会熔断"
              min={1}
              max={32}
              disabled={mode !== 'react'}
              value={maxSteps}
              onChange={setMaxSteps}
            />
            <Button loading={loading} disabled={!prompt.trim() || loading} onClick={() => void run()}>
              运行
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="运行后，ReAct 会列出每一步工具调用。">
            {trace && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                <Stack gap={6}>
                  <Text size="sm" c="dimmed">
                    finalAnswer
                  </Text>
                  <pre className="stream-text">{trace.finalAnswer}</pre>
                  {trace.reachedMaxSteps && (
                    <Badge color="orange" variant="light" w="fit-content">
                      reachedMaxSteps
                    </Badge>
                  )}
                </Stack>
                {trace.steps.length > 0 && (
                  <Timeline active={trace.steps.length - 1} bulletSize={18} lineWidth={2} color="seaweed">
                    {trace.steps.map((step) => (
                      <Timeline.Item key={step.index} title={`${step.index}. ${step.toolName}`}>
                        {step.assistantText && (
                          <Text size="sm" mb={6}>
                            {step.assistantText}
                          </Text>
                        )}
                        <Code block>{step.toolArgs}</Code>
                        <Text size="sm" mt={6} c="dimmed">
                          {step.toolResult}
                        </Text>
                      </Timeline.Item>
                    ))}
                  </Timeline>
                )}
                <RawJsonAccordion value={trace} />
              </Stack>
            )}
            {framework && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={framework.usage} />
                <pre className="stream-text">{framework.content}</pre>
                <RawJsonAccordion value={framework} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
