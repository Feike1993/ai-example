/** 与 Java `server.servlet.context-path` 对齐。 */
export const API_BASE = '/ai-example'

/** HTTP 失败，带状态码与响应正文，方便面板展示。 */
export class ApiError extends Error {
  readonly status: number
  readonly body: string

  constructor(status: number, body: string) {
    super(body ? `HTTP ${status}: ${body}` : `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

export type TokenUsage = {
  prompt: number | null
  completion: number | null
  total: number | null
}

export type ChatResponse = { content: string; usage: TokenUsage | null }

export type Ticket = {
  title: string
  priority: string
  labels: string[]
  summary: string
}

export type TicketResponse = { ticket: Ticket; usage: TokenUsage | null }

export type ToolChatResponse = { content: string; usage: TokenUsage | null }

export type AgentStep = {
  index: number
  assistantText: string
  toolName: string
  toolArgs: string
  toolResult: string
}

export type AgentTrace = {
  finalAnswer: string
  steps: AgentStep[]
  reachedMaxSteps: boolean
}

export type FrameworkResponse = { content: string; usage: TokenUsage | null }

export type McpChatResponse = {
  content: string
  toolNames: string[]
  usage: TokenUsage | null
  mode?: string
}

export type McpToolsResponse = {
  toolNames: string[]
  mode?: string
  error?: string
}

export type RagSource = {
  id: string
  source: string
  excerpt: string
  metadata?: Record<string, unknown>
  vectorRank?: number | null
  keywordRank?: number | null
  rrfScore?: number | null
}

export type RagQueryResponse = {
  answer: string
  sources: RagSource[]
  retrievalEmpty: boolean
  usage: TokenUsage | null
  retrievalMode?: string
  queryExpansion?: string
  hypotheticalDocument?: string | null
}

export type RagCompareResponse = {
  vector: RagQueryResponse
  hybrid: RagQueryResponse
}

export type RagExpansionView = {
  queryExpansion: string
  sources: RagSource[]
  retrievalEmpty: boolean
  hypotheticalDocument?: string | null
  rewrittenQuery?: string | null
}

export type RagExpansionCompareResponse = {
  none: RagExpansionView
  rewrite: RagExpansionView
  hyde: RagExpansionView
}

export type EvalCaseResult = {
  id: string
  passed: boolean
  durationMs: number
  steps: number
  toolFailures: number
  error: string | null
  answer: string | null
  usage: TokenUsage | null
}

export type EvalRunResponse = {
  total: number
  passed: number
  failed: number
  cases: EvalCaseResult[]
  usageSummary: TokenUsage | null
}

export type RagIngestResponse = {
  chunkCount: number
  sources: string[]
}

export type ContextChatResponse = {
  sessionId: string
  strategy: string
  content: string
  rawMessageCount: number
  sentMessageCount: number
  approxTokens: number
  droppedCount: number
  summary: string | null
  usage: TokenUsage | null
  store?: string
}

export type MemorySource = {
  id: string
  excerpt: string
  metadata?: Record<string, unknown>
}

export type MemoryRememberResponse = {
  id: string
  userId: string
  text: string
  duplicate?: boolean
  updated?: boolean
}

export type MemoryRecallResponse = {
  userId: string
  sources: MemorySource[]
  empty: boolean
}

export type MemoryChatResponse = {
  answer: string
  sources: MemorySource[]
  retrievalEmpty: boolean
  userId: string
  usage: TokenUsage | null
}

export type MultiAgentStep = {
  index: number
  assistantText: string
  toolName: string
  toolArgs: string
  toolResult: string
}

export type MultiAgentTrace = {
  name: string
  role: string
  steps: MultiAgentStep[]
  error: string | null
}

export type MultiAgentResult = {
  finalAnswer: string
  agents: MultiAgentTrace[]
  reachedMaxSteps: boolean
}

export type ProviderView = {
  id: string
  label: string
  model: string
  configured: boolean
}

export type ProviderListResponse = {
  defaultProvider: string
  providers: ProviderView[]
}

/**
 * 把 token 用量格式化为可读文案；无数据时返回 null。
 */
export function formatTokenUsage(usage: TokenUsage | null | undefined): string | null {
  if (!usage) {
    return null
  }
  const parts: string[] = []
  if (usage.prompt != null) {
    parts.push(`prompt ${usage.prompt}`)
  }
  if (usage.completion != null) {
    parts.push(`completion ${usage.completion}`)
  }
  if (usage.total != null) {
    parts.push(`total ${usage.total}`)
  }
  return parts.length > 0 ? parts.join(' · ') : null
}

/**
 * GET JSON。非 2xx 抛 {@link ApiError}。
 */
export async function getJson<T>(path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(path)
  } catch {
    throw new Error('无法连接后端，请先在 8080 端口启动 Java 服务')
  }
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  if (!text) {
    throw new ApiError(response.status, '空响应')
  }
  return JSON.parse(text) as T
}

/**
 * 拉取可切换的 LLM Provider 清单。
 */
export function listProviders(): Promise<ProviderListResponse> {
  return getJson<ProviderListResponse>(`${API_BASE}/providers`)
}

/**
 * POST JSON 并解析响应。非 2xx 抛 {@link ApiError}。
 */
export async function postJson<T>(path: string, body: unknown): Promise<T> {
  return requestJson<T>(path, 'POST', body)
}

/**
 * PUT JSON 并解析响应。非 2xx 抛 {@link ApiError}。
 */
export async function putJson<T>(path: string, body: unknown): Promise<T> {
  return requestJson<T>(path, 'PUT', body)
}

async function requestJson<T>(path: string, method: 'POST' | 'PUT', body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new Error('无法连接后端，请先在 8080 端口启动 Java 服务')
  }

  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  if (!text) {
    throw new ApiError(response.status, '空响应')
  }
  return JSON.parse(text) as T
}

/**
 * 把 SSE data 还原成模型增量。Spring 常把 String 编成 JSON 字符串。
 */
function decodeSseData(raw: string): string {
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed === 'string') {
      return parsed
    }
  } catch {
    // 非 JSON 片段，按原文追加
  }
  return raw
}

/**
 * 订阅 Chat SSE。返回取消函数；服务端关流时调用 onDone。
 *
 * @param prompt   用户问题，会放进 query
 * @param provider Provider id，空则走后端默认
 * @param onChunk 每个 token / 片段
 * @param onDone 正常结束或主动取消
 * @param onError 尚未收到任何数据就断开（多为后端未启动）
 */
export function streamChat(
  prompt: string,
  provider: string,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
): () => void {
  const params = new URLSearchParams({ prompt })
  if (provider) {
    params.set('provider', provider)
  }
  const url = `${API_BASE}/chat/stream?${params.toString()}`
  const source = new EventSource(url)
  let received = false
  let closed = false

  const finish = (error?: Error) => {
    if (closed) {
      return
    }
    closed = true
    source.close()
    if (error) {
      onError(error)
    } else {
      onDone()
    }
  }

  source.onmessage = (event) => {
    if (event.data === '' || event.data === '[DONE]') {
      return
    }
    received = true
    onChunk(decodeSseData(event.data))
  }

  source.onerror = () => {
    if (received) {
      finish()
      return
    }
    finish(new Error('无法连接后端或流式中断，请确认 Java 服务已在 8080 启动'))
  }

  return () => finish()
}

/**
 * 订阅 Agent ReAct SSE。先处理 event:steps，再追加终答增量。
 */
export function streamAgentReact(
  prompt: string,
  provider: string,
  maxSteps: number | undefined,
  onSteps: (steps: AgentStep[], reachedMaxSteps: boolean) => void,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
): () => void {
  const params = new URLSearchParams({ prompt })
  if (provider) {
    params.set('provider', provider)
  }
  if (typeof maxSteps === 'number') {
    params.set('maxSteps', String(maxSteps))
  }
  const url = `${API_BASE}/agent/react/stream?${params.toString()}`
  const source = new EventSource(url)
  let received = false
  let closed = false

  const finish = (error?: Error) => {
    if (closed) {
      return
    }
    closed = true
    source.close()
    if (error) {
      onError(error)
    } else {
      onDone()
    }
  }

  source.addEventListener('steps', (event) => {
    received = true
    try {
      const payload = JSON.parse((event as MessageEvent).data) as {
        steps?: AgentStep[]
        reachedMaxSteps?: boolean
      }
      onSteps(payload.steps ?? [], payload.reachedMaxSteps === true)
    } catch {
      onSteps([], false)
    }
  })

  source.onmessage = (event) => {
    if (event.data === '' || event.data === '[DONE]') {
      return
    }
    received = true
    onChunk(decodeSseData(event.data))
  }

  source.onerror = () => {
    if (received) {
      finish()
      return
    }
    finish(new Error('无法连接后端或流式中断，请确认 Java 服务已在 8080 启动'))
  }

  return () => finish()
}

/**
 * 订阅 RAG SSE。先处理 event:sources，再追加文本增量。
 */
export function streamRag(
  question: string,
  provider: string,
  retrievalMode: 'vector' | 'hybrid',
  rewriteQuery: boolean,
  onSources: (sources: RagSource[], retrievalEmpty: boolean) => void,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
): () => void {
  const params = new URLSearchParams({ question })
  if (provider) {
    params.set('provider', provider)
  }
  if (retrievalMode === 'hybrid') {
    params.set('retrievalMode', 'hybrid')
  }
  if (rewriteQuery) {
    params.set('rewriteQuery', 'true')
  }
  const url = `${API_BASE}/rag/query/stream?${params.toString()}`
  const source = new EventSource(url)
  let received = false
  let closed = false

  const finish = (error?: Error) => {
    if (closed) {
      return
    }
    closed = true
    source.close()
    if (error) {
      onError(error)
    } else {
      onDone()
    }
  }

  source.addEventListener('sources', (event) => {
    received = true
    try {
      const payload = JSON.parse((event as MessageEvent).data) as {
        sources?: RagSource[]
        retrievalEmpty?: boolean
      }
      onSources(payload.sources ?? [], payload.retrievalEmpty === true)
    } catch {
      onSources([], false)
    }
  })

  source.onmessage = (event) => {
    if (event.data === '' || event.data === '[DONE]') {
      return
    }
    received = true
    onChunk(decodeSseData(event.data))
  }

  source.onerror = () => {
    if (received) {
      finish()
      return
    }
    finish(new Error('无法连接后端或流式中断，请确认 Java 服务已在 8080 启动，且已 ingest'))
  }

  return () => finish()
}

/**
 * 把未知异常转成可展示文案。
 */
export function describeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return '未知错误'
}
