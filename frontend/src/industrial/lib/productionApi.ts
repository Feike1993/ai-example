import { API_BASE, ApiError } from '../../api'
import type { ProductionSource } from './sseClient'

/** 工业级链路的接口前缀，与样例路径不重叠。 */
export const PRODUCTION_BASE = `${API_BASE}/api/v1`

export type ProductionChatAnswer = {
  answer: string
  sources: ProductionSource[]
  retrievalEmpty: boolean
  retrievalMode: string
}

export type ProductionIngestResult = {
  corpus: string
  chunkCount: number
  sources: string[]
}

/**
 * 同步问答。
 *
 * @param body      问题与可选 provider / topK
 * @param fetchImpl 便于测试注入
 * @returns 答案与来源
 * @throws ApiError HTTP 失败或后端不可达
 */
export async function postChat(
  body: { question: string; provider?: string; topK?: number },
  fetchImpl: typeof fetch = fetch,
): Promise<ProductionChatAnswer> {
  return requestJson<ProductionChatAnswer>(`${PRODUCTION_BASE}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, fetchImpl)
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
 * @param params 查询参数
 * @returns 完整 URL
 */
export function chatStreamUrl(params: { question: string; provider?: string; topK?: number }): string {
  const query = new URLSearchParams({ question: params.question })
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
