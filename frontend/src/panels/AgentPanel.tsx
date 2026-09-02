import { Alert, Badge, Button, Code, NumberInput, SegmentedControl, Stack, Text, Textarea, Timeline } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useRef, useState } from 'react'
import {
  describeError,
  formatTokenUsage,
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

/** 进阶样例对同一面板的侧重：试玩区文案与默认传输不同，避免 18/19 看起来一样。 */
export type AgentPanelFocus = 'loop' | 'sse' | 'usage'

const FOCUS_HINT: Record<AgentPanelFocus, string> = {
  loop: 'ReAct 可同步或 SSE；Framework 为托管 tool-calling。进阶样例再拆开看逐步事件与 usage。',
  sse: '本样例焦点：SSE 路径上 tool_call / tool_result 边跑边推。看左侧 Timeline 是否逐步出现，不必等全部工具结束。',
  usage: '本样例焦点：多跳 Token 合计。同步看 Trace.usage / usageCalls；SSE 看 event:usage。网关未返回则为 null。',
}

/**
 * Agent 样例：显式 ReAct Loop 与框架托管对照；ReAct 支持 SSE 逐步 tool 事件与 usage。
 */
export function AgentPanel({
  provider,
  guide = agentGuide,
  title = 'Agent Loop',
  defaultTransport = 'sync',
  focus = 'loop',
  hint,
}: {
  provider: string
  guide?: SampleGuideData
  title?: string
  defaultTransport?: Transport
  /** 试玩侧重：进阶 18=sse、19=usage，基础 Agent 用 loop */
  focus?: AgentPanelFocus
  hint?: string
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

  // 切换进阶菜单时父组件会换 key 重挂；若仅改 props，也把传输对齐到样例默认值
  useEffect(() => {
    setTransport(defaultTransport)
  }, [defaultTransport])

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
  const usageCalls = trace?.usageCalls ?? streamUsage?.calls ?? null
  const workbenchHint = hint ?? FOCUS_HINT[focus]
  const emptyHint =
    focus === 'sse'
      ? '选「SSE 逐步」运行后，Timeline 会随 tool_call / tool_result 增量更新。'
      : focus === 'usage'
        ? '运行后重点看 Token 合计与 usageCalls（多跳 LLM 调用次数）。'
        : '运行后，ReAct 会列出每一步工具调用。'

  return (
    <SampleFrame guide={guide}>
      <Workbench
        title={title}
        hint={workbenchHint}
        streaming={streaming}
        form={
          <Stack gap="md">
            {focus === 'sse' && (
              <Alert color="seaweed" variant="light" title="观察点：逐步事件">
                传输保持「SSE 逐步」。运行中 Timeline 应先出 tool，再出终答；同步模式看不到实时事件。
              </Alert>
            )}
            {focus === 'usage' && (
              <Alert color="seaweed" variant="light" title="观察点：用量累加">
                默认「同步」看 JSON 里的 usage / usageCalls。也可切 SSE，结果区会展示 event:usage 合计。
              </Alert>
            )}
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
          <ResultBody error={error} emptyHint={emptyHint}>
            {(trace || streaming || streamText || streamSteps.length > 0) && mode === 'react' && (
              <Stack gap="lg">
                {focus === 'usage' ? (
                  <Stack gap={6}>
                    <Text size="sm" fw={600}>
                      TokenUsage 合计
                    </Text>
                    <RequestMeta
                      elapsedMs={transport === 'sse' ? ttftMs : elapsedMs}
                      elapsedLabel={transport === 'sse' ? 'TTFT' : '耗时'}
                      usage={displayUsage ?? null}
                    />
                    <GroupUsageCalls calls={usageCalls} usage={displayUsage ?? null} />
                  </Stack>
                ) : (
                  <RequestMeta
                    elapsedMs={transport === 'sse' ? ttftMs : elapsedMs}
                    elapsedLabel={transport === 'sse' ? 'TTFT' : '耗时'}
                    usage={displayUsage ?? null}
                  />
                )}
                {focus !== 'usage' && usageCalls != null && (
                  <Text size="sm" c="dimmed">
                    usageCalls={usageCalls}
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
                  <Stack gap={6}>
                    {focus === 'sse' && (
                      <Text size="sm" fw={600}>
                        工具轨迹（SSE 增量）
                      </Text>
                    )}
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
                  </Stack>
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

/** Usage 样例：突出调用次数与是否拿到合计。 */
function GroupUsageCalls({
  calls,
  usage,
}: {
  calls: number | null
  usage: TokenUsage | AgentStreamUsage | null
}) {
  const usageText = formatTokenUsage(usage)
  return (
    <Stack gap={4}>
      {calls != null ? (
        <Badge color="seaweed" variant="light" w="fit-content">
          usageCalls={calls}
        </Badge>
      ) : (
        <Text size="sm" c="dimmed">
          usageCalls 尚未返回（流式进行中或网关未给 usage）
        </Text>
      )}
      {!usageText && (
        <Text size="sm" c="dimmed">
          Token 字段为 null 时表示网关未返回，前端不编造。
        </Text>
      )}
    </Stack>
  )
}
