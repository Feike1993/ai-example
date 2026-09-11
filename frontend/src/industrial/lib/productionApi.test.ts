import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api'
import { getAccessToken, setSession, UNAUTHORIZED_EVENT } from './auth'
import { getAudit, getMe, postChat, postIngest, postToolProbe, chatStreamUrl } from './productionApi'

describe('productionApi 鉴权头', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('登录后请求带 Authorization', async () => {
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      username: 'alice', tenant: 'tenant-a', roles: ['USER'],
    }), { status: 200, headers: { 'X-RateLimit-Remaining': '29' } }))
    await getMe(fetchImpl as unknown as typeof fetch)
    const [, init] = fetchImpl.mock.calls[0] as unknown as [RequestInfo, RequestInit?]
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer tok-1')
  })

  it('401 清 token 并派发 unauthorized', async () => {
    setSession('expired', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const seen: string[] = []
    window.addEventListener(UNAUTHORIZED_EVENT, () => seen.push('u'))
    const fetchImpl = vi.fn(async () => new Response('{"error":"未认证"}', { status: 401 }))
    await expect(postChat({ question: 'hi' }, fetchImpl as unknown as typeof fetch)).rejects.toBeInstanceOf(ApiError)
    expect(getAccessToken()).toBeNull()
    expect(seen).toEqual(['u'])
  })

  it('403 显示后端业务提示', async () => {
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const fetchImpl = vi.fn(async () => new Response(
      '{"code":"ingest_forbidden","message":"重建索引需要 ADMIN 角色"}', { status: 403 },
    ))

    await expect(postIngest(fetchImpl as unknown as typeof fetch)).rejects.toMatchObject({
      status: 403,
      code: 'ingest_forbidden',
      message: '重建索引需要 ADMIN 角色',
    })
  })

  it('忽略 Boot 默认的 Forbidden 英文词', async () => {
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const fetchImpl = vi.fn(async () => new Response(
      '{"error":"Forbidden","status":403,"path":"/ai-example/api/v1/rag/ingest"}',
      { status: 403 },
    ))

    const error = await postIngest(fetchImpl as unknown as typeof fetch).catch((err: unknown) => err) as Error
    expect(error).toMatchObject({ status: 403, message: 'HTTP 403' })
    expect(error.message).not.toContain('Forbidden')
  })

  it('审计列表按 JSON 数组返回', async () => {
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([
      {
        id: 1,
        tenantId: 'tenant-a',
        principal: 'alice',
        action: 'chat',
        path: '/api/v1/chat',
        status: 200,
        runId: null,
        questionSha256: 'abc',
        durationMs: 12,
        ip: '127.0.0.1',
        createdAt: '2026-09-09T01:00:00Z',
      },
    ]), { status: 200 }))
    const rows = await getAudit(10, fetchImpl as unknown as typeof fetch)
    expect(rows).toHaveLength(1)
    expect(rows[0].action).toBe('chat')
  })

  it('工具探针 POST JSON', async () => {
    setSession('tok-1', { username: 'alice', tenant: 'tenant-a', roles: ['USER'] })
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      tool: 'rebuild_index', allowed: false, denied: true,
    }), { status: 200 }))
    const result = await postToolProbe('rebuild_index', fetchImpl as unknown as typeof fetch)
    expect(result.denied).toBe(true)
    const call = fetchImpl.mock.calls[0] as unknown as [RequestInfo, RequestInit?]
    expect(String(call[0])).toContain('/agent/tool-probe')
    expect(call[1]?.method).toBe('POST')
  })

  it('流式地址默认不带 queryExpansion，HyDE 才写入', () => {
    expect(chatStreamUrl({ question: 'hi', topK: 4 })).not.toContain('queryExpansion')
    expect(chatStreamUrl({ question: 'hi', queryExpansion: 'hyde' })).toContain('queryExpansion=hyde')
  })
})
