import { API_BASE, ApiError } from '../../api'
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
}

export type ProductionIngestResult = {
  corpus: string
  chunkCount: number
  sources: string[]
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
 * 同步问答。
 *
 * @param body      问题与可选 sessionId / provider / topK
 * @param fetchImpl 便于测试注入
 * @returns 答案与来源
 * @throws ApiError HTTP 失败或后端不可达；会话被占用时为 409
 */
export async function postChat(
  body: { question: string; sessionId?: string; provider?: string; topK?: number },
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
 * 重建生产语料索引。
 *
 * @param fetchImpl 便于测试注入
 * @returns 入库结果
 * @throws ApiError HTTP 失败或后端不可达
 */
export async function postIngest(fetchImpl: typeof fetch = fetch): Promise<ProductionIngestResult> {
  return requestJson<ProductionIngestResult>(`${PRODUCTION_BASE}/rag/ingest`, { method: 'POST' }, fetchImpl)
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
  return `${PRODUCTION_BASE}/chat/stream?${query.toString()}`
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

async function requestJson<T>(url: string, init: RequestInit, fetchImpl: typeof fetch): Promise<T> {
  let response: Response
  try {
    response = await fetchImpl(url, init)
  } catch {
    throw new ApiError(0, '后端服务不可用，请检查部署状态或稍后重试')
  }
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, text)
  }
  return JSON.parse(text) as T
}
