import { Badge, Button, Code, NumberInput, SegmentedControl, Stack, Text, Textarea, Timeline } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useRef, useState } from 'react'
import {
  describeError,
  postJson,
  streamAgentReact,
  API_BASE,
  type AgentStep,
  type AgentStreamUsage,
  type AgentTrace,
  type FrameworkResponse,
  type TokenUsage,
} from '../api'
import { MarkdownBody } from '../components/MarkdownBody'
import { RawJsonAccordion } from '../components/RawJsonAccordion'
import { RequestMeta } from '../components/RequestMeta'
import { ResultBody } from '../components/ResultBody'
import { SampleFrame } from '../components/SampleFrame'
import { Workbench } from '../components/Workbench'
import { agentGuide } from '../guides'
import type { SampleGuideData } from '../guides'

type Mode = 'react' | 'framework'
type Transport = 'sync' | 'sse'

/**
 * Agent 样例：显式 ReAct Loop 与框架托管对照；ReAct 支持 SSE 逐步 tool 事件与 usage。
 */
export function AgentPanel({
  provider,
  guide = agentGuide,
  title = 'Agent Loop',
  defaultTransport = 'sync',
}: {
  provider: string
  guide?: SampleGuideData
  title?: string
  defaultTransport?: Transport
}) {
  const [prompt, setPrompt] = useState('北京天气怎么样？再算 3+5')
  const [maxSteps, setMaxSteps] = useState<number | string>(8)
  const [mode, setMode] = useState<Mode>('react')
  const [transport, setTransport] = useState<Transport>(defaultTransport)
  const [loading, setLoading] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [trace, setTrace] = useState<AgentTrace | null>(null)
  const [framework, setFramework] = useState<FrameworkResponse | null>(null)
  const [streamText, setStreamText] = useState('')
  const [streamSteps, setStreamSteps] = useState<AgentStep[]>([])
  const [reachedMaxSteps, setReachedMaxSteps] = useState(false)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const [ttftMs, setTtftMs] = useState<number | null>(null)
  const [streamUsage, setStreamUsage] = useState<AgentStreamUsage | null>(null)
  const stopRef = useRef<(() => void) | null>(null)
  const pendingCalls = useRef<Map<string, AgentStep>>(new Map())

  useEffect(() => {
    return () => {
      stopRef.current?.()
    }
  }, [])

  const runSync = async () => {
    setError(null)
    setTrace(null)
    setFramework(null)
    setStreamText('')
    setStreamSteps([])
    setReachedMaxSteps(false)
    setElapsedMs(null)
    setTtftMs(null)
    setStreamUsage(null)
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

  const upsertStep = (partial: Partial<AgentStep> & { index: number; toolName: string }) => {
    const key = `${partial.index}:${partial.toolName}`
    const prev = pendingCalls.current.get(key)
    const next: AgentStep = {
      index: partial.index,
      assistantText: partial.assistantText ?? prev?.assistantText ?? '',
      toolName: partial.toolName,
      toolArgs: partial.toolArgs ?? prev?.toolArgs ?? '',
      toolResult: partial.toolResult ?? prev?.toolResult ?? '',
    }
    pendingCalls.current.set(key, next)
    const list = Array.from(pendingCalls.current.values()).sort((a, b) => a.index - b.index)
    setStreamSteps(list)
  }

  const runSse = () => {
    setError(null)
    setTrace(null)
    setFramework(null)
    setStreamText('')
    setStreamSteps([])
    setReachedMaxSteps(false)
    setElapsedMs(null)
    setTtftMs(null)
    setStreamUsage(null)
    pendingCalls.current = new Map()
    setLoading(true)
    setStreaming(true)
    const started = performance.now()
    let first = true
    let acc = ''
    let latestSteps: AgentStep[] = []
    let latestReached = false
    let latestUsage: TokenUsage | null = null
    let latestCalls = 0
    const stepsLimit = typeof maxSteps === 'number' ? maxSteps : undefined

    stopRef.current = streamAgentReact(prompt, provider, stepsLimit, {
      onSteps: (steps, reached) => {
        latestSteps = steps
        latestReached = reached
        setStreamSteps(steps)
        setReachedMaxSteps(reached)
        for (const step of steps) {
          pendingCalls.current.set(`${step.index}:${step.toolName}`, step)
        }
      },
      onToolCall: (payload) => {
        upsertStep({
          index: payload.index,
          toolName: payload.toolName,
          assistantText: payload.assistantText,
          toolArgs: payload.toolArgs,
        })
      },
      onToolResult: (payload) => {
        upsertStep({
          index: payload.index,
          toolName: payload.toolName,
          toolResult: payload.toolResult,
        })
      },
      onChunk: (chunk) => {
        if (first) {
          first = false
          setTtftMs(Math.round(performance.now() - started))
        }
        acc += chunk
        setStreamText(acc)
      },
      onUsage: (usage) => {
        latestUsage = {
          prompt: usage.prompt ?? null,
          completion: usage.completion ?? null,
          total: usage.total ?? null,
        }
        latestCalls = usage.calls ?? 0
        setStreamUsage(usage)
      },
      onDone: (reached) => {
        latestReached = reached
        setReachedMaxSteps(reached)
      },
      onComplete: () => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setElapsedMs(Math.round(performance.now() - started))
        const steps =
          pendingCalls.current.size > 0
            ? Array.from(pendingCalls.current.values()).sort((a, b) => a.index - b.index)
            : latestSteps
        setTrace({
          finalAnswer: acc,
          steps,
          reachedMaxSteps: latestReached,
          usage: latestUsage,
          usageCalls: latestCalls,
        })
      },
      onError: (err) => {
        stopRef.current = null
        setStreaming(false)
        setLoading(false)
        setError(err.message)
        notifications.show({ color: 'red', title: 'Agent SSE 失败', message: err.message })
      },
    })
  }

  const onSubmit = () => {
    if (loading && streaming) {
      stopRef.current?.()
      stopRef.current = null
      setStreaming(false)
      setLoading(false)
      return
    }
    if (mode === 'react' && transport === 'sse') {
      runSse()
      return
    }
    void runSync()
  }

  const displaySteps = trace?.steps ?? streamSteps
  const displayAnswer = streaming ? streamText : (trace?.finalAnswer ?? streamText)
  const displayReached = trace?.reachedMaxSteps ?? reachedMaxSteps
  const displayUsage = mode === 'framework' ? framework?.usage : (trace?.usage ?? streamUsage)

  return (
    <SampleFrame guide={guide}>
      <Workbench
        title={title}
        hint="ReAct 可同步或 SSE 逐步 tool；Framework 为托管 tool-calling。第十期展示 usage 累加。"
        streaming={streaming}
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
            {mode === 'react' && (
              <SegmentedControl
                fullWidth
                value={transport}
                onChange={(value) => setTransport(value as Transport)}
                data={[
                  { value: 'sync', label: '同步' },
                  { value: 'sse', label: 'SSE 逐步' },
                ]}
              />
            )}
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
            <Button
              loading={loading && !streaming}
              disabled={!prompt.trim() || (loading && !streaming)}
              onClick={onSubmit}
            >
              {streaming ? '停止' : '运行'}
            </Button>
          </Stack>
        }
        result={
          <ResultBody error={error} emptyHint="运行后，ReAct 会列出每一步工具调用。">
            {(trace || streaming || streamText || streamSteps.length > 0) && mode === 'react' && (
              <Stack gap="lg">
                <RequestMeta
                  elapsedMs={transport === 'sse' ? ttftMs : elapsedMs}
                  elapsedLabel={transport === 'sse' ? 'TTFT' : '耗时'}
                  usage={displayUsage ?? null}
                />
                {(trace?.usageCalls != null || streamUsage?.calls != null) && (
                  <Text size="sm" c="dimmed">
                    usageCalls={trace?.usageCalls ?? streamUsage?.calls}
                  </Text>
                )}
                <Stack gap={6}>
                  <Text size="sm" c="dimmed">
                    finalAnswer
                  </Text>
                  <MarkdownBody streaming={streaming}>{displayAnswer}</MarkdownBody>
                  {displayReached && (
                    <Badge color="orange" variant="light" w="fit-content">
                      reachedMaxSteps
                    </Badge>
                  )}
                </Stack>
                {displaySteps.length > 0 && (
                  <Timeline active={displaySteps.length - 1} bulletSize={18} lineWidth={2} color="seaweed">
                    {displaySteps.map((step) => (
                      <Timeline.Item key={`${step.index}-${step.toolName}`} title={`${step.index}. ${step.toolName}`}>
                        {step.assistantText && (
                          <Text size="sm" mb={6}>
                            {step.assistantText}
                          </Text>
                        )}
                        <Code block>{step.toolArgs}</Code>
                        <Text size="sm" mt={6} c="dimmed">
                          {step.toolResult || (streaming ? '…' : '')}
                        </Text>
                      </Timeline.Item>
                    ))}
                  </Timeline>
                )}
                {!streaming && trace && <RawJsonAccordion value={trace} />}
              </Stack>
            )}
            {framework && mode === 'framework' && (
              <Stack gap="sm">
                <RequestMeta elapsedMs={elapsedMs} usage={framework.usage} />
                <MarkdownBody>{framework.content}</MarkdownBody>
                <RawJsonAccordion value={framework} />
              </Stack>
            )}
          </ResultBody>
        }
      />
    </SampleFrame>
  )
}
