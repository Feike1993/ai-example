import { Badge, Button, Code, Stack, Text, Textarea, Timeline } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import {
  describeError,
  postJson,
  API_BASE,
  type MultiAgentResult,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { multiAgentGuide } from '../guides'

/**
 * 多 Agent：Orchestrator + 调研 / 执笔轨迹。
 */
export function MultiAgentPanel({ provider }: { provider: string }) {
  const [prompt, setPrompt] = useState('查一下北京天气，再写一句给游客的出行建议')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<MultiAgentResult | null>(null)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)

  const run = async () => {
    setError(null)
    setResult(null)
    setElapsedMs(null)
    setLoading(true)
    const started = performance.now()
    try {
      const data = await postJson<MultiAgentResult>(`${API_BASE}/multiagent/run`, {
        prompt,
        provider,
      })
      setElapsedMs(Math.round(performance.now() - started))
      setResult(data)
    } catch (err) {
      const message = describeError(err)
      setError(message)
      notifications.show({ color: 'red', title: '多 Agent 失败', message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <SampleFrame guide={multiAgentGuide}>
      <Workbench
        title="多 Agent"
        hint="POST /ai-example/multiagent/run；按角色查看交接与工具步骤。"
        form={
          <Stack gap="md">
            <Textarea
              label="prompt"
              minRows={8}
              autosize
              value={prompt}
              onChange={(event) => setPrompt(event.currentTarget.value)}
            />
            <Button loading={loading} disabled={!prompt.trim() || loading} onClick={() => void run()}>
              运行
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="运行后按 orchestrator / researcher / writer 分段展示。">
            {result && (
              <Stack gap="lg">
                <RequestMeta elapsedMs={elapsedMs} />
                <Stack gap={6}>
                  <Text size="sm" c="dimmed">
                    finalAnswer
                  </Text>
                  <MarkdownBody>{result.finalAnswer}</MarkdownBody>
                  {result.reachedMaxSteps && (
                    <Badge color="orange" variant="light" w="fit-content">
                      reachedMaxSteps
                    </Badge>
                  )}
                </Stack>
                {result.agents.map((agent, agentIndex) => (
                  <Stack key={`${agent.name}-${agentIndex}`} gap="sm">
                    <Text fw={600}>
                      {agent.role}{' '}
                      <Badge size="sm" variant="light">
                        {agent.name}
                      </Badge>
                    </Text>
                    {agent.error && (
                      <Text size="sm" c="red">
                        {agent.error}
                      </Text>
                    )}
                    {agent.steps.length > 0 && (
                      <Timeline active={agent.steps.length - 1} bulletSize={18} lineWidth={2} color="seaweed">
                        {agent.steps.map((step) => (
                          <Timeline.Item
                            key={`${agent.name}-${step.index}-${step.toolName}`}
                            title={`${step.index}. ${step.toolName || 'step'}`}
                          >
                            {step.assistantText && (
                              <Text size="sm" mb={6}>
                                {step.assistantText}
                              </Text>
                            )}
                            {step.toolArgs && <Code block>{step.toolArgs}</Code>}
                            {step.toolResult && (
                              <Text size="sm" mt={6} c="dimmed">
                                {step.toolResult}
                              </Text>
                            )}
                          </Timeline.Item>
                        ))}
                      </Timeline>
                    )}
                  </Stack>
                ))}
                <RawJsonAccordion value={result} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
