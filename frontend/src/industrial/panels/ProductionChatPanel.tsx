import { Alert, Badge, Button, Group, NumberInput, SegmentedControl, Stack, Text, Textarea } from '@mantine/core'
import { useEffect, useRef, useState } from 'react'
import { ApiError, describeError } from '../../api'
import { MarkdownBody } from '../../components/MarkdownBody'
import { ResultBody } from '../../components/ResultBody'
import { Workbench } from '../../components/Workbench'
import { authHeaders, notifyUnauthorized, rememberTraceId } from '../lib/auth'
import { mergeAgentSteps } from '../lib/agentSteps'
import { agentStreamUrl, chatStreamUrl, clearSession, getSession, postIngest, resumeUrl } from '../lib/productionApi'
import { toTurns, type Turn } from '../lib/sessionTurns'
import {
  StreamAbortedError,
  StreamServerError,
  streamRun,
  type AgentStep,
  type ProductionSource,
  type SourcesPayload,
} from '../lib/sseClient'

type ProductionChatPanelProps = {
  provider: string
}

const SESSION_STORAGE_KEY = 'ai-example.production.sessionId'

/** 会话被占用时后端返回的错误码，与 ProductionChatService.SESSION_BUSY 一致。 */
const SESSION_BUSY = 'session_busy'

/**
 * 生产链路多轮问答面板。
 *
 * 与样例面板的关键差别有两处。
 *
 * 收尾判定：这里区分「完成」「取消」「断流」「服务端报错」四种终态，只有拿到 done 才展示为成功。
 * 断流时保留已收到的正文，但明确标注结果不完整——把不完整的回答当成完整答案展示，
 * 是这类界面最危险的默认行为。
 *
 * 会话一致性：sessionId 由后端在首条 meta 事件里给出，前端只负责回传，不自己造。
 * 后端才知道这一轮到底有没有落库，前端自造 id 会在落库失败时把界面和数据库的认知拉开。
 * 同理，未落库的一轮要显式标出来，否则用户会以为下一轮能接上它。
 */
export function ProductionChatPanel({ provider }: ProductionChatPanelProps) {
  const [question, setQuestion] = useState('这个项目的 RAG 是怎么做的？')
  const [mode, setMode] = useState<'chat' | 'agent'>('chat')
  const [topK, setTopK] = useState<number | string>(4)
  const [sessionId, setSessionId] = useState<string | null>(
    () => localStorage.getItem(SESSION_STORAGE_KEY),
  )
  const [turns, setTurns] = useState<Turn[]>([])
  const [pending, setPending] = useState<Turn | null>(null)
  const [meta, setMeta] = useState<{ runId: string; retrievalMode?: string } | null>(null)
  const [malformed, setMalformed] = useState(0)
  const [status, setStatus] = useState<'idle' | 'streaming' | 'done' | 'cancelled' | 'aborted'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [ingesting, setIngesting] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  // 刷新页面后把上次会话的历史拉回来，否则界面看着像新会话、后端却还带着上下文。
  // 这里直接读 localStorage 而不依赖 sessionId 状态：只该在挂载时拉一次，
  // 跟着 sessionId 变化重拉会盖掉本地的「未落库」「不完整」标记——那些状态数据库里没有。
  useEffect(() => {
    const stored = localStorage.getItem(SESSION_STORAGE_KEY)
    if (!stored) {
      return
    }
    let cancelled = false
    void getSession(stored)
      .then((view) => {
        if (!cancelled) {
          setTurns(toTurns(view.messages))
        }
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [])

  const rememberSession = (id: string | null) => {
    setSessionId(id)
    if (id) {
      localStorage.setItem(SESSION_STORAGE_KEY, id)
    } else {
      localStorage.removeItem(SESSION_STORAGE_KEY)
    }
  }

  const onStream = async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setError(null)
    setMeta(null)
    setMalformed(0)
    setBusy(false)
    setStatus('streaming')

    const asked = question
    let current: Turn = { question: asked, answer: '', sources: [], usage: null, steps: [] }
    setPending(current)
    const update = (patch: Partial<Turn>) => {
      current = { ...current, ...patch }
      setPending(current)
    }

    let persisted = true
    try {
      const url = mode === 'agent'
        ? agentStreamUrl({ question: asked, sessionId, provider })
        : chatStreamUrl({ question: asked, sessionId, provider, topK: Number(topK) || undefined })
      const result = await streamRun(
        url,
        {
          signal: controller.signal,
          resumeUrl,
          maxResumes: 2,
          headers: authHeaders(),
          handlers: {
            onMeta: (payload) => {
              setMeta({ runId: payload.runId })
              if (typeof payload.traceId === 'string') {
                rememberTraceId(payload.traceId)
              }
              if (typeof payload.sessionId === 'string') {
                rememberSession(payload.sessionId)
              }
            },
            onSources: (payload: SourcesPayload) => {
              update({ sources: payload.sources ?? [] })
              setMeta((prev) =>
                prev ? { ...prev, retrievalMode: payload.retrievalMode } : prev,
              )
            },
            onDelta: (text) => update({ answer: current.answer + text }),
            onStep: (step) => {
              update({ steps: mergeAgentSteps(current.steps ?? [], step) })
            },
            onUsage: (usage) => update({ usage }),
            onDone: (payload) => {
              persisted = payload.persisted !== false
              if (typeof payload.sessionId === 'string') {
                rememberSession(payload.sessionId)
              }
            },
            onMalformed: () => setMalformed((count) => count + 1),
          },
        },
      )
      if (result.outcome === 'cancelled') {
        // 取消的一轮后端没有落库，留在待定区并标注，不混进历史
        setStatus('cancelled')
        update({ incomplete: true })
      } else {
        setStatus('done')
        setTurns((prev) => [...prev, { ...current, unpersisted: !persisted }])
        setPending(null)
        setQuestion('')
      }
    } catch (err) {
      setStatus('aborted')
      update({ incomplete: true })
      if (err instanceof StreamServerError) {
        if (err.code === SESSION_BUSY) {
          // 这不是生成失败，而是同一会话上一轮还没结束。走单独的黄色提示而不是红色「请求失败」：
          // ResultBody 一旦拿到 error 就只渲染错误框，历史会整段消失，
          // 而这种情况下用户最需要看到的恰恰是「上一轮还在那里跑」。
          setBusy(true)
          setPending(null)
        } else {
          setError(`${err.message}（错误码 ${err.code}）`)
        }
      } else if (err instanceof StreamAbortedError) {
        setError(err.message)
      } else if (err instanceof ApiError && err.status === 401) {
        notifyUnauthorized()
      } else {
        setError(describeError(err))
      }
    } finally {
      abortRef.current = null
    }
  }

  const onStop = () => {
    abortRef.current?.abort()
    abortRef.current = null
  }

  const onNewSession = () => {
    abortRef.current?.abort()
    abortRef.current = null
    rememberSession(null)
    setTurns([])
    setPending(null)
    setMeta(null)
    setError(null)
    setBusy(false)
    setStatus('idle')
  }

  const onClearSession = async () => {
    if (!sessionId) {
      onNewSession()
      return
    }
    try {
      await clearSession(sessionId)
      onNewSession()
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        notifyUnauthorized()
      }
      setError(err instanceof ApiError ? err.message : describeError(err))
    }
  }

  const onIngest = async () => {
    setIngesting(true)
    setError(null)
    try {
      const result = await postIngest()
      setError(null)
      setPending({
        question: '重建语料',
        answer: `已重建语料 ${result.corpus}：${result.chunkCount} 块，来自 ${result.sources.length} 个文件。`,
        sources: [],
        usage: null,
      })
      setStatus('done')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : describeError(err))
      setStatus('aborted')
    } finally {
      setIngesting(false)
    }
  }

  const streaming = status === 'streaming'
  const visible = pending ? [...turns, pending] : turns

  return (
    <Workbench
      title="生产问答"
      hint={
        mode === 'agent'
          ? 'Agent 模式会多发 step 事件。被角色策略拒绝的工具带 denied:true，不会静默吞掉。'
          : '严格 SSE 契约：meta → sources → delta → usage → done。只有收到 done 才算成功，且该轮才会写入会话历史。'
      }
      streaming={streaming}
      form={
        <Stack gap="sm">
          <SegmentedControl
            value={mode}
            onChange={(value) => setMode(value as 'chat' | 'agent')}
            data={[
              { value: 'chat', label: '问答' },
              { value: 'agent', label: 'Agent' },
            ]}
          />
          <Textarea
            label="问题"
            autosize
            minRows={3}
            value={question}
            onChange={(event) => setQuestion(event.currentTarget.value)}
          />
          {mode === 'chat' ? (
            <NumberInput label="topK" min={1} max={20} value={topK} onChange={setTopK} />
          ) : null}
          <Group gap="sm">
            <Button onClick={onStream} loading={streaming} disabled={!question.trim()}>
              流式提问
            </Button>
            <Button variant="default" onClick={onStop} disabled={!streaming}>
              停止
            </Button>
            <Button variant="subtle" onClick={onNewSession} disabled={streaming}>
              新会话
            </Button>
            <Button variant="subtle" color="red" onClick={onClearSession} disabled={streaming || !sessionId}>
              清空会话
            </Button>
            <Button variant="subtle" onClick={onIngest} loading={ingesting}>
              重建语料
            </Button>
          </Group>
          <Text size="xs" c="dimmed">
            当前模型：{provider} · 会话 {sessionId ?? '未建立'} · 已存 {turns.length} 轮
          </Text>
        </Stack>
      }
      result={
        <ResultBody error={error} emptyHint="提交问题后，这里按事件顺序展示检索来源与流式答案，并保留整段多轮对话。">
          {busy ? (
            <Alert color="yellow" variant="light" title="会话正忙">
              该会话上一轮对话还在进行。等它结束，或点「新会话」另起一段。
            </Alert>
          ) : null}
          {/* 状态行从发起请求那刻就要在：取消或断流可能一个字都没收到，
              此时更需要告诉用户「发生了什么」，而不是退回空态提示 */}
          {status !== 'idle' || visible.length > 0 ? (
            <Stack gap="md">
              <Group gap="xs">
                <StatusBadge status={status} />
                {meta?.retrievalMode ? <Badge variant="light">{meta.retrievalMode}</Badge> : null}
                {malformed > 0 ? (
                  <Badge color="orange" variant="light">
                    {malformed} 条事件无法解析
                  </Badge>
                ) : null}
              </Group>
              {visible.map((turn, index) => (
                <TurnView
                  key={`${index}-${turn.question}`}
                  turn={turn}
                  streaming={streaming && index === visible.length - 1}
                />
              ))}
              {meta?.runId ? (
                <Text size="xs" c="dimmed">
                  runId {meta.runId}
                </Text>
              ) : null}
            </Stack>
          ) : null}
        </ResultBody>
      }
    />
  )
}

function TurnView({ turn, streaming }: { turn: Turn; streaming: boolean }) {
  return (
    <Stack gap="xs" className="industrial-turn">
      <Text size="sm" fw={600} className="industrial-turn-question">
        {turn.question}
      </Text>
      {turn.incomplete ? (
        <Alert color="orange" variant="light" title="结果可能不完整">
          未收到 done 事件，以下内容是断流前已接收的部分，本轮不会写入会话历史。
        </Alert>
      ) : null}
      {turn.unpersisted ? (
        <Alert color="yellow" variant="light" title="本轮未计入历史">
          回答已生成，但写入会话历史失败。后续轮次看不到这一问一答。
        </Alert>
      ) : null}
      <MarkdownBody streaming={streaming}>{turn.answer}</MarkdownBody>
      {turn.steps && turn.steps.length > 0 ? <StepList steps={turn.steps} /> : null}
      {turn.sources.length > 0 ? <SourceList sources={turn.sources} /> : null}
      {turn.usage ? (
        <Text size="xs" c="dimmed">
          answerChars {turn.usage.answerChars} · sourceCount {turn.usage.sourceCount}
        </Text>
      ) : null}
    </Stack>
  )
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { color: string; label: string }> = {
    idle: { color: 'gray', label: '待命' },
    streaming: { color: 'blue', label: '生成中' },
    done: { color: 'teal', label: '已完成' },
    cancelled: { color: 'gray', label: '已取消' },
    aborted: { color: 'orange', label: '未完成' },
  }
  const view = map[status] ?? map.idle
  return (
    <Badge color={view.color} variant="light">
      {view.label}
    </Badge>
  )
}

function StepList({ steps }: { steps: AgentStep[] }) {
  return (
    <Stack gap={6}>
      <Text size="sm" fw={600}>
        工具步骤（{steps.length}）
      </Text>
      {steps.map((step) => (
        <div key={`${step.index}-${step.toolName}`} className="industrial-source">
          <Group gap="xs">
            <Text size="xs" fw={600}>
              {step.index + 1}. {step.toolName}
            </Text>
            {step.denied ? (
              <Badge color="red" variant="light" size="xs">
                已拒绝
              </Badge>
            ) : null}
          </Group>
          {step.assistantText ? (
            <Text size="xs" c="dimmed">
              {step.assistantText}
            </Text>
          ) : null}
          {step.toolArgs ? (
            <Text size="xs" c="dimmed">
              args {step.toolArgs}
            </Text>
          ) : null}
          {step.toolResult ? <Text size="sm">{step.toolResult}</Text> : null}
        </div>
      ))}
    </Stack>
  )
}

function SourceList({ sources }: { sources: ProductionSource[] }) {
  return (
    <Stack gap={6}>
      <Text size="sm" fw={600}>
        检索来源（{sources.length}）
      </Text>
      {sources.map((source) => (
        <div key={source.id} className="industrial-source">
          <Text size="xs" c="dimmed">
            {source.source}
            {source.heading ? ` · ${source.heading}` : ''}
          </Text>
          <Text size="sm">{source.excerpt}</Text>
        </div>
      ))}
    </Stack>
  )
}
