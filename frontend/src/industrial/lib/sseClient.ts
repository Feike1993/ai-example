/**
 * 工业级 SSE 客户端：fetch + ReadableStream 手写解析，替代原生 EventSource。
 *
 * 换实现的三个理由，缺一不可：
 * 1. EventSource 无法区分「服务端正常结束」和「连接断了」——两者都只触发 onerror，
 *    样例里只能靠「收到过数据就算成功」兜底，于是断流会被当成完整回答展示给用户；
 * 2. EventSource 不能带自定义请求头，鉴权和 Last-Event-ID 手动重连都做不了；
 * 3. EventSource 自带的自动重连不带业务语义，会从头重放而不是从缺口续传。
 *
 * 这里的契约是：只有收到 `done` 事件才算成功，其余任何结束方式都是失败。
 */

import { ApiError } from '../../api'

/** 与后端 StreamEventTypeEnum 一一对应。 */
export const STREAM_EVENT_TYPES = [
  'meta',
  'sources',
  'delta',
  'step',
  'usage',
  'done',
  'error',
] as const

export type StreamEventType = (typeof STREAM_EVENT_TYPES)[number]

/** SSE 原始帧，尚未做业务解析。 */
export type SseFrame = {
  id?: string
  event?: string
  data: string
}

/** 单步 Agent 状态，与后端 step 事件负载对齐。 */
export type AgentStep = {
  index: number
  toolName: string
  assistantText: string
  toolArgs: string
  toolResult: string
  denied?: boolean
}

export type ProductionSource = {
  id: string
  source: string
  excerpt: string
  heading?: string | null
}

export type SourcesPayload = {
  sources: ProductionSource[]
  retrievalEmpty: boolean
  retrievalMode: string
}

export type UsagePayload = {
  answerChars: number
  sourceCount: number
}

/**
 * done 事件负载。
 *
 * `persisted=false` 表示回答生成成功但没能写进会话历史——后端刻意不把它降级成 error，
 * 界面要据此提示「本轮未计入历史」，否则用户会以为下一轮还能接上。
 */
export type DonePayload = {
  retrievalEmpty?: boolean
  sessionId?: string | null
  persisted?: boolean
}

/** 无法解析的事件，计数而非静默丢弃——静默丢弃会让前端显示不全却毫无线索。 */
export type MalformedEvent = {
  reason: 'unknown_event' | 'bad_json' | 'bad_id'
  raw: string
}

export type RunHandlers = {
  onMeta?: (payload: { runId: string } & Record<string, unknown>) => void
  onSources?: (payload: SourcesPayload) => void
  onDelta?: (text: string) => void
  onStep?: (step: Partial<AgentStep> & { index: number; toolName: string }) => void
  onUsage?: (payload: UsagePayload) => void
  onDone?: (payload: DonePayload) => void
  onMalformed?: (event: MalformedEvent) => void
  /** seq 出现缺口；返回后客户端会尝试续传（若配置了 resume）。 */
  onGap?: (expected: number, received: number) => void
}

export type RunResult = {
  outcome: 'done' | 'cancelled'
  runId: string | null
  lastSeq: number
  malformedCount: number
}

export type StreamRunOptions = {
  signal?: AbortSignal
  handlers?: RunHandlers
  headers?: Record<string, string>
  /**
   * 根据 runId 计算续传 URL。返回 null 表示不续传。
   * 不配置时，任何非 done 结束都直接失败——这是默认且最安全的行为。
   */
  resumeUrl?: (runId: string) => string | null
  /** 续传次数上限，防止服务端持续掉线时无限重连。 */
  maxResumes?: number
  fetchImpl?: typeof fetch
}

/** 流在收到 done 之前结束。这是 EventSource 版本会误判成成功的那种情况。 */
export class StreamAbortedError extends Error {
  readonly runId: string | null
  readonly lastSeq: number

  constructor(runId: string | null, lastSeq: number) {
    super('响应在完成前中断（未收到 done 事件），结果可能不完整，请重试')
    this.name = 'StreamAbortedError'
    this.runId = runId
    this.lastSeq = lastSeq
  }
}

/** 服务端显式发来的 error 事件。 */
export class StreamServerError extends Error {
  readonly code: string
  readonly runId: string | null

  constructor(code: string, message: string, runId: string | null) {
    super(message)
    this.name = 'StreamServerError'
    this.code = code
    this.runId = runId
  }
}

/**
 * 把缓冲区切成完整帧，返回剩余不完整片段。
 *
 * 单独抽成纯函数是为了能直接喂任意切分的 chunk 做单测——
 * 真实网络下一个事件被拆成两个 chunk 是常态，这正是最容易写错的地方。
 *
 * @param buffer 累积缓冲区
 * @returns 已完整的帧与尚未收全的尾部
 */
export function parseSseChunk(buffer: string): { frames: SseFrame[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const frames: SseFrame[] = []
  let cursor = 0

  while (true) {
    const boundary = normalized.indexOf('\n\n', cursor)
    if (boundary < 0) {
      break
    }
    const block = normalized.slice(cursor, boundary)
    cursor = boundary + 2
    const frame = parseFrame(block)
    if (frame) {
      frames.push(frame)
    }
  }

  return { frames, rest: normalized.slice(cursor) }
}

function parseFrame(block: string): SseFrame | null {
  let id: string | undefined
  let event: string | undefined
  const dataLines: string[] = []

  for (const line of block.split('\n')) {
    // 注释帧（心跳）没有业务含义，但必须被消费掉，否则会把后续帧顶乱
    if (line.startsWith(':') || line.trim() === '') {
      continue
    }
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    const rawValue = colon < 0 ? '' : line.slice(colon + 1)
    const value = rawValue.startsWith(' ') ? rawValue.slice(1) : rawValue

    if (field === 'id') {
      id = value
    } else if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }

  if (id === undefined && event === undefined && dataLines.length === 0) {
    return null
  }
  return { id, event, data: dataLines.join('\n') }
}

/**
 * 消费一条生产链路 SSE 流，直到 done、error、取消或断流。
 *
 * @param url     流地址
 * @param options 回调、取消信号、续传策略
 * @returns done 或 cancelled 的结果
 * @throws StreamServerError 服务端 error 事件
 * @throws StreamAbortedError 未收到 done 就结束
 * @throws ApiError HTTP 层失败
 */
export async function streamRun(url: string, options: StreamRunOptions = {}): Promise<RunResult> {
  const doFetch = options.fetchImpl ?? fetch
  const handlers = options.handlers ?? {}
  const maxResumes = options.maxResumes ?? 0

  const state = {
    runId: null as string | null,
    lastSeq: -1,
    malformedCount: 0,
  }

  let target = url
  let resumeHeader: string | null = null
  let resumesLeft = options.resumeUrl ? maxResumes : 0

  while (true) {
    const outcome = await consume(target, resumeHeader, doFetch, handlers, options.headers, options.signal, state)

    if (outcome === 'done' || outcome === 'cancelled') {
      return { outcome, runId: state.runId, lastSeq: state.lastSeq, malformedCount: state.malformedCount }
    }

    // outcome === 'interrupted'：没收到终态事件就断了
    const next = state.runId && resumesLeft > 0 ? options.resumeUrl?.(state.runId) : null
    if (!next) {
      throw new StreamAbortedError(state.runId, state.lastSeq)
    }
    resumesLeft -= 1
    target = next
    resumeHeader = String(state.lastSeq)
  }
}

type ConsumeOutcome = 'done' | 'cancelled' | 'interrupted'

async function consume(
  url: string,
  lastEventId: string | null,
  doFetch: typeof fetch,
  handlers: RunHandlers,
  extraHeaders: Record<string, string> | undefined,
  signal: AbortSignal | undefined,
  state: { runId: string | null; lastSeq: number; malformedCount: number },
): Promise<ConsumeOutcome> {
  const headers: Record<string, string> = { Accept: 'text/event-stream', ...extraHeaders }
  if (lastEventId !== null) {
    headers['Last-Event-ID'] = lastEventId
  }

  let response: Response
  try {
    response = await doFetch(url, { headers, signal })
  } catch (err) {
    if (isAbort(err, signal)) {
      return 'cancelled'
    }
    throw new ApiError(0, '后端服务不可用，请检查部署状态或稍后重试')
  }

  if (!response.ok) {
    const body = await safeText(response)
    throw new ApiError(response.status, body)
  }
  if (!response.body) {
    throw new ApiError(response.status, '响应缺少流式正文')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const { frames, rest } = parseSseChunk(buffer)
      buffer = rest
      for (const frame of frames) {
        const outcome = handleFrame(frame, handlers, state)
        if (outcome) {
          return outcome
        }
      }
    }
  } catch (err) {
    if (err instanceof StreamServerError) {
      // 服务端明确说失败了，这不是断流，续传也没有意义
      throw err
    }
    if (isAbort(err, signal)) {
      return 'cancelled'
    }
    // 读取中途出错等同于断流，交给上层决定是续传还是报错
    return 'interrupted'
  } finally {
    void reader.cancel().catch(() => undefined)
  }

  if (signal?.aborted) {
    return 'cancelled'
  }
  return 'interrupted'
}

/**
 * 分发单帧；返回终态则整条流结束。
 */
function handleFrame(
  frame: SseFrame,
  handlers: RunHandlers,
  state: { runId: string | null; lastSeq: number; malformedCount: number },
): ConsumeOutcome | null {
  const type = frame.event
  if (!type || !(STREAM_EVENT_TYPES as readonly string[]).includes(type)) {
    state.malformedCount += 1
    handlers.onMalformed?.({ reason: 'unknown_event', raw: `${frame.event ?? ''}:${frame.data}` })
    return null
  }

  const seq = Number(frame.id)
  if (frame.id === undefined || !Number.isInteger(seq)) {
    state.malformedCount += 1
    handlers.onMalformed?.({ reason: 'bad_id', raw: frame.data })
    return null
  }
  if (seq <= state.lastSeq) {
    // 续传时服务端可能多回放一条，重复的直接丢弃而不是当成缺口
    return null
  }
  if (seq !== state.lastSeq + 1) {
    handlers.onGap?.(state.lastSeq + 1, seq)
  }
  state.lastSeq = seq

  let payload: unknown
  try {
    payload = JSON.parse(frame.data)
  } catch {
    state.malformedCount += 1
    handlers.onMalformed?.({ reason: 'bad_json', raw: frame.data })
    return null
  }

  switch (type as StreamEventType) {
    case 'meta': {
      const meta = payload as { runId: string } & Record<string, unknown>
      state.runId = typeof meta?.runId === 'string' ? meta.runId : state.runId
      handlers.onMeta?.(meta)
      return null
    }
    case 'sources':
      handlers.onSources?.(payload as SourcesPayload)
      return null
    case 'delta':
      handlers.onDelta?.(typeof payload === 'string' ? payload : String(payload))
      return null
    case 'step':
      handlers.onStep?.(payload as Partial<AgentStep> & { index: number; toolName: string })
      return null
    case 'usage':
      handlers.onUsage?.(payload as UsagePayload)
      return null
    case 'done':
      handlers.onDone?.((payload ?? {}) as DonePayload)
      return 'done'
    case 'error': {
      const err = payload as { code?: string; message?: string }
      throw new StreamServerError(err?.code ?? 'internal_error', err?.message ?? '服务内部错误', state.runId)
    }
  }
}

function isAbort(err: unknown, signal: AbortSignal | undefined): boolean {
  if (signal?.aborted) {
    return true
  }
  return err instanceof DOMException && err.name === 'AbortError'
}

async function safeText(response: Response): Promise<string> {
  try {
    return await response.text()
  } catch {
    return ''
  }
}
