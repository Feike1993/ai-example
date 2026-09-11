import { API_BASE, ApiError } from '../../api'
import {
  authHeaders,
  notifyUnauthorized,
  rememberRateRemaining,
  type ProductionUser,
} from './auth'
import { parseProductionError } from './productionError'
import type { ProductionSource } from './sseClient'

/** 工业级链路的接口前缀，与样例路径不重叠。 */
export const PRODUCTION_BASE = `${API_BASE}/api/v1`

export type ProductionChatAnswer = {
  sessionId: string | null
  answer: string
  sources: ProductionSource[]
  retrievalEmpty: boolean
  retrievalMode: string
  historyMessages: number
  persisted: boolean
  queryExpansion?: string
}

export type ProductionIngestJob = {
  jobId: string
  status: string
  corpus: string
  chunkCount?: number | null
  sources?: string[]
  errorMessage?: string | null
  instanceId?: string | null
}

export type ProductionIngestResult = ProductionIngestJob

export type TokenResponse = {
  token: string
  username: string
  tenant: string
  roles: string[]
  expiresIn: number
}

export type AuditRow = {
  id: number
  tenantId: string
  principal: string
  action: string
  path: string
  status: number
  runId: string | null
  questionSha256: string | null
  durationMs: number | null
  ip: string | null
  createdAt: string
  toolName?: string | null
  denied?: boolean | null
}

export type OpsSnapshot = {
  chatRuns: number
  agentRuns: number
  retrievalEmpty: number
  guardrailBlocked: number
  toolDenied: number
  rateLimited: number
  authFail: number
  ingestSubmitted?: number
  ingestSucceeded?: number
  ingestFailed?: number
  mediaAccepted?: number
  mediaRejected?: number
  chatDurationCount: number
  meters: number
  traceId: string | null
  ingestJobId?: string | null
  ingestStatus?: string | null
  ingestChunkCount?: number | null
  ingestError?: string | null
}

/** 一条已落库的会话消息，与后端 SessionMessage 对齐。 */
export type SessionMessage = {
  seq: number
  turnId: string
  role: 'user' | 'assistant' | 'system'
  content: string
  runId: string | null
  createdAt: string | null
}

export type SessionView = {
  sessionId: string
  messages: SessionMessage[]
}

/**
 * 换取 JWT。
 *
 * @param username  演示用户名
 * @param password  密码
 * @param fetchImpl 便于测试注入
 */
export async function postToken(
  username: string,
  password: string,
  fetchImpl: typeof fetch = fetch,
): Promise<TokenResponse> {
  return requestJson<TokenResponse>(`${PRODUCTION_BASE}/auth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }, fetchImpl, false)
}

/**
 * 当前令牌对应的主体。
 *
 * @param fetchImpl 便于测试注入
 */
export async function getMe(fetchImpl: typeof fetch = fetch): Promise<ProductionUser> {
  return requestJson<ProductionUser>(`${PRODUCTION_BASE}/me`, { method: 'GET' }, fetchImpl)
}

/**
 * 本租户最近审计。
 *
 * @param limit     条数
 * @param fetchImpl 便于测试注入
 */
export async function getAudit(limit = 50, fetchImpl: typeof fetch = fetch): Promise<AuditRow[]> {
  return requestJson<AuditRow[]>(`${PRODUCTION_BASE}/audit?limit=${limit}`, { method: 'GET' }, fetchImpl)
}

/**
 * 指标快照。
 *
 * @param fetchImpl 便于测试注入
 */
export async function getOpsSnapshot(fetchImpl: typeof fetch = fetch): Promise<OpsSnapshot> {
  return requestJson<OpsSnapshot>(`${PRODUCTION_BASE}/ops/snapshot`, { method: 'GET' }, fetchImpl)
}

/**
 * 最近一次本机 k6 摘要。
 *
 * @param fetchImpl 便于测试注入
 */
export async function getLoadtestSummary(
  fetchImpl: typeof fetch = fetch,
): Promise<Record<string, unknown>> {
  return requestJson<Record<string, unknown>>(`${PRODUCTION_BASE}/ops/loadtest`, { method: 'GET' }, fetchImpl)
}

/**
 * 当前身份允许的 Agent 工具。
 *
 * @param fetchImpl 便于测试注入
 */
export async function getAgentTools(fetchImpl: typeof fetch = fetch): Promise<{ tools: string[] }> {
  return requestJson<{ tools: string[] }>(`${PRODUCTION_BASE}/agent/tools`, { method: 'GET' }, fetchImpl)
}

/**
 * 无 LLM 的工具策略探针。
 *
 * @param tool      工具名
 * @param fetchImpl 便于测试注入
 */
export async function postToolProbe(
  tool: string,
  fetchImpl: typeof fetch = fetch,
): Promise<{ tool: string; allowed: boolean; denied: boolean }> {
  return requestJson<{ tool: string; allowed: boolean; denied: boolean }>(
    `${PRODUCTION_BASE}/agent/tool-probe`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tool }) },
    fetchImpl,
  )
}

/**
 * 从 SSE 事件日志重建 run 步骤。
 *
 * @param runId     run
 * @param fetchImpl 便于测试注入
 */
export async function getRunTimeline(
  runId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<{
  runId: string
  state: string
  tenant: string
  mode: string | null
  steps: Array<Record<string, unknown>>
  done: Record<string, unknown> | null
  error: Record<string, unknown> | null
}> {
  return requestJson(`${PRODUCTION_BASE}/ops/runs/${encodeURIComponent(runId)}`, { method: 'GET' }, fetchImpl)
}

/**
 * 用当前 KEK 重加密 prod_secret。仅 ADMIN。
 *
 * @param fetchImpl 便于测试注入
 */
export async function postSecretRotate(
  fetchImpl: typeof fetch = fetch,
): Promise<{ rewritten: number; kekId: string }> {
  return requestJson<{ rewritten: number; kekId: string }>(
    `${PRODUCTION_BASE}/secrets/rotate`,
    { method: 'POST' },
    fetchImpl,
  )
}

/**
 * 同步问答。
 *
 * @param body      问题与可选 sessionId / provider / topK
 * @param fetchImpl 便于测试注入
 * @returns 答案与来源
 * @throws ApiError HTTP 失败或后端不可达；会话被占用时为 409
 */
export async function postChat(
  body: {
    question: string
    sessionId?: string
    provider?: string
    topK?: number
    queryExpansion?: string
  },
  fetchImpl: typeof fetch = fetch,
): Promise<ProductionChatAnswer> {
  return requestJson<ProductionChatAnswer>(`${PRODUCTION_BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, fetchImpl)
}

/**
 * 读取会话历史。
 *
 * @param sessionId 会话 id
 * @param fetchImpl 便于测试注入
 * @returns 按 seq 升序的消息
 * @throws ApiError HTTP 失败或后端不可达
 */
export async function getSession(
  sessionId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<SessionView> {
  return requestJson<SessionView>(
    `${PRODUCTION_BASE}/sessions/${encodeURIComponent(sessionId)}`,
    { method: 'GET' },
    fetchImpl,
  )
}

/**
 * 清空会话。
 *
 * @param sessionId 会话 id
 * @param fetchImpl 便于测试注入
 * @returns 会话原先是否存在
 * @throws ApiError HTTP 失败或后端不可达
 */
export async function clearSession(
  sessionId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<{ sessionId: string; existed: boolean }> {
  return requestJson<{ sessionId: string; existed: boolean }>(
    `${PRODUCTION_BASE}/sessions/${encodeURIComponent(sessionId)}`,
    { method: 'DELETE' },
    fetchImpl,
  )
}

/**
 * 投递重建生产语料索引（202 任务）。
 *
 * @param fetchImpl 便于测试注入
 * @returns 入库任务
 * @throws ApiError HTTP 失败或后端不可达
 */
export async function postIngest(fetchImpl: typeof fetch = fetch): Promise<ProductionIngestJob> {
  return requestJson<ProductionIngestJob>(`${PRODUCTION_BASE}/rag/ingest`, { method: 'POST' }, fetchImpl)
}

/**
 * 查询入库任务。
 *
 * @param jobId     任务 id
 * @param fetchImpl 便于测试注入
 */
export async function getIngestJob(
  jobId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<ProductionIngestJob> {
  return requestJson<ProductionIngestJob>(
    `${PRODUCTION_BASE}/rag/ingest/jobs/${encodeURIComponent(jobId)}`,
    { method: 'GET' },
    fetchImpl,
  )
}

/**
 * 流式问答地址。
 *
 * @param params 查询参数；不带 sessionId 时后端新建一个，并在首条 meta 事件里回传
 * @returns 完整 URL
 */
export function chatStreamUrl(params: {
  question: string
  sessionId?: string | null
  provider?: string
  topK?: number
  queryExpansion?: string
}): string {
  const query = new URLSearchParams({ question: params.question })
  if (params.sessionId) {
    query.set('sessionId', params.sessionId)
  }
  if (params.provider) {
    query.set('provider', params.provider)
  }
  if (params.topK) {
    query.set('topK', String(params.topK))
  }
  if (params.queryExpansion && params.queryExpansion !== 'none') {
    query.set('queryExpansion', params.queryExpansion)
  }
  return `${PRODUCTION_BASE}/chat/stream?${query.toString()}`
}

/**
 * 带图流式问答地址（POST multipart）。
 */
export function chatStreamPostUrl(): string {
  return `${PRODUCTION_BASE}/chat/stream`
}

/** 一轮最多几张图，与后端默认 PRODUCTION_MEDIA_MAX_IMAGES 对齐；后端仍是权威。 */
export const PRODUCTION_MAX_IMAGES = 3

/**
 * 组装图文流式 FormData。多张图用同名 `image` 重复 part，兼容第九阶段单图 curl。
 *
 * @param params 问句与图片列表
 */
export function chatStreamForm(params: {
  question: string
  sessionId?: string | null
  provider?: string
  topK?: number
  queryExpansion?: string
  images: File[]
}): FormData {
  const form = new FormData()
  form.set('question', params.question)
  if (params.sessionId) {
    form.set('sessionId', params.sessionId)
  }
  if (params.provider) {
    form.set('provider', params.provider)
  }
  if (params.topK) {
    form.set('topK', String(params.topK))
  }
  if (params.queryExpansion && params.queryExpansion !== 'none') {
    form.set('queryExpansion', params.queryExpansion)
  }
  for (const image of params.images) {
    form.append('image', image)
  }
  return form
}

/**
 * 媒体探针，不调模型。
 *
 * @param file      图片或音频
 * @param fetchImpl fetch
 */
export async function postMediaProbe(
  file: File,
  fetchImpl: typeof fetch = fetch,
): Promise<{ mime: string; bytes: number; sha256: string }> {
  const form = new FormData()
  form.set('file', file)
  return requestJson(`${PRODUCTION_BASE}/media/probe`, { method: 'POST', body: form }, fetchImpl)
}

/**
 * 语音转写。
 *
 * @param audio     音频
 * @param fetchImpl fetch
 */
export async function postTranscribe(
  audio: Blob,
  fetchImpl: typeof fetch = fetch,
): Promise<{ text: string }> {
  const form = new FormData()
  const file = audio instanceof File ? audio : new File([audio], 'audio.webm', { type: audio.type || 'audio/webm' })
  form.set('audio', file)
  return requestJson(`${PRODUCTION_BASE}/speech/transcribe`, { method: 'POST', body: form }, fetchImpl)
}

/**
 * 语音合成，返回可播放的 Blob。
 *
 * @param text      终答文本
 * @param fetchImpl fetch
 */
export async function postSpeak(text: string, fetchImpl: typeof fetch = fetch): Promise<Blob> {
  const headers = new Headers({ 'Content-Type': 'application/json' })
  const auth = authHeaders()
  for (const [key, value] of Object.entries(auth)) {
    headers.set(key, value)
  }
  let response: Response
  try {
    response = await fetchImpl(`${PRODUCTION_BASE}/speech/speak`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ text }),
    })
  } catch {
    throw new ApiError(0, '后端服务不可用，请检查部署状态或稍后重试')
  }
  rememberRateRemaining(response.headers.get('X-RateLimit-Remaining'))
  if (response.status === 401) {
    notifyUnauthorized()
  }
  if (!response.ok) {
    const raw = await response.text()
    const parsed = parseProductionError(response.status, raw)
    throw new ApiError(response.status, raw, parsed.message, parsed.code)
  }
  return response.blob()
}

/**
 * 流式 Agent 地址。
 *
 * @param params 任务与会话
 * @returns 完整 URL
 */
export function agentStreamUrl(params: {
  question: string
  sessionId?: string | null
  provider?: string
}): string {
  const query = new URLSearchParams({ question: params.question })
  if (params.sessionId) {
    query.set('sessionId', params.sessionId)
  }
  if (params.provider) {
    query.set('provider', params.provider)
  }
  return `${PRODUCTION_BASE}/agent/stream?${query.toString()}`
}

/**
 * 断线续传地址。
 *
 * @param runId 首条 meta 事件里的 runId
 * @returns 完整 URL
 */
export function resumeUrl(runId: string): string {
  return `${PRODUCTION_BASE}/runs/${encodeURIComponent(runId)}/stream`
}

async function requestJson<T>(
  url: string,
  init: RequestInit,
  fetchImpl: typeof fetch,
  withAuth = true,
): Promise<T> {
  const headers = new Headers(init.headers)
  if (withAuth) {
    const auth = authHeaders()
    for (const [key, value] of Object.entries(auth)) {
      if (!headers.has(key)) {
        headers.set(key, value)
      }
    }
  }
  let response: Response
  try {
    response = await fetchImpl(url, { ...init, headers })
  } catch {
    throw new ApiError(0, '后端服务不可用，请检查部署状态或稍后重试')
  }
  rememberRateRemaining(response.headers.get('X-RateLimit-Remaining'))
  const text = await response.text()
  if (response.status === 401 && withAuth) {
    notifyUnauthorized()
  }
  if (!response.ok) {
    const parsed = parseProductionError(response.status, text)
    throw new ApiError(response.status, text, parsed.message, parsed.code)
  }
  return JSON.parse(text) as T
}
