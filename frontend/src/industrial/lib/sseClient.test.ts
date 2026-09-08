import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api'
import {
  StreamAbortedError,
  StreamServerError,
  parseSseChunk,
  streamRun,
  type MalformedEvent,
} from './sseClient'
import { fakeFetch, frame } from './testing/sseFixtures'

const META = frame(0, 'meta', JSON.stringify({ runId: 'run-1' }))
const SOURCES = frame(
  1,
  'sources',
  JSON.stringify({ sources: [{ id: 'd1', source: 'a.md', excerpt: '正文' }], retrievalEmpty: false, retrievalMode: 'hybrid' }),
)
const DELTA_A = frame(2, 'delta', JSON.stringify('答'))
const DELTA_B = frame(3, 'delta', JSON.stringify('案'))
const USAGE = frame(4, 'usage', JSON.stringify({ answerChars: 2, sourceCount: 1 }))
const DONE = frame(5, 'done', JSON.stringify({ retrievalEmpty: false }))

describe('parseSseChunk', () => {
  it('把完整帧切出来并保留不完整的尾部', () => {
    const { frames, rest } = parseSseChunk(`${META}id:1\nevent:delta\ndata:"半`)

    expect(frames).toHaveLength(1)
    expect(frames[0].event).toBe('meta')
    expect(rest).toBe('id:1\nevent:delta\ndata:"半')
  })

  it('忽略心跳注释帧但不打乱后续事件', () => {
    const { frames } = parseSseChunk(`:hb\n\n${META}${DELTA_A}`)

    expect(frames.map((f) => f.event)).toEqual(['meta', 'delta'])
  })

  it('支持 CRLF 与多行 data', () => {
    const { frames } = parseSseChunk('id:0\r\nevent:delta\r\ndata:"第一行"\r\ndata:"第二行"\r\n\r\n')

    expect(frames[0].data).toBe('"第一行"\n"第二行"')
  })
})

describe('streamRun 事件顺序', () => {
  it('按契约顺序回调，并在 done 后返回成功', async () => {
    const seen: string[] = []
    const result = await streamRun('/stream', {
      fetchImpl: fakeFetch([META, SOURCES, DELTA_A, DELTA_B, USAGE, DONE]),
      handlers: {
        onMeta: () => seen.push('meta'),
        onSources: () => seen.push('sources'),
        onDelta: (text) => seen.push(`delta:${text}`),
        onUsage: () => seen.push('usage'),
      },
    })

    expect(seen).toEqual(['meta', 'sources', 'delta:答', 'delta:案', 'usage'])
    expect(result.outcome).toBe('done')
    expect(result.runId).toBe('run-1')
    expect(result.lastSeq).toBe(5)
  })

  it('事件被拆进任意 chunk 边界仍能还原', async () => {
    const whole = META + SOURCES + DELTA_A + DELTA_B + USAGE + DONE
    const chunks = whole.match(/[\s\S]{1,7}/g) ?? []
    const deltas: string[] = []

    const result = await streamRun('/stream', {
      fetchImpl: fakeFetch(chunks),
      handlers: { onDelta: (text) => deltas.push(text) },
    })

    expect(deltas).toEqual(['答', '案'])
    expect(result.outcome).toBe('done')
  })

  it('seq 缺口会被上报', async () => {
    const gaps: Array<[number, number]> = []
    await streamRun('/stream', {
      fetchImpl: fakeFetch([META, frame(4, 'delta', '"跳号"'), frame(5, 'done', '{}')]),
      handlers: { onGap: (expected, received) => gaps.push([expected, received]) },
    })

    expect(gaps).toEqual([[1, 4]])
  })

  it('续传时重复回放的事件不会被当成缺口或重复渲染', async () => {
    const deltas: string[] = []
    await streamRun('/stream', {
      fetchImpl: fakeFetch([META, DELTA_A, DELTA_A, DELTA_B, DONE]),
      handlers: { onDelta: (text) => deltas.push(text) },
    })

    expect(deltas).toEqual(['答', '案'])
  })
})

describe('streamRun 中途断流', () => {
  it('未收到 done 就结束时抛 StreamAbortedError，而不是当成功', async () => {
    const promise = streamRun('/stream', {
      fetchImpl: fakeFetch([META, SOURCES, DELTA_A]),
    })

    await expect(promise).rejects.toBeInstanceOf(StreamAbortedError)
    await expect(promise).rejects.toMatchObject({ runId: 'run-1', lastSeq: 2 })
  })

  it('usage 之后断流同样算失败，只差 done 一条也不放过', async () => {
    await expect(
      streamRun('/stream', { fetchImpl: fakeFetch([META, SOURCES, DELTA_A, USAGE]) }),
    ).rejects.toBeInstanceOf(StreamAbortedError)
  })

  it('配置续传时会带 Last-Event-ID 重连并补齐剩余事件', async () => {
    const calls: Array<{ url: string; lastEventId: string | null }> = []
    const first = fakeFetch([META, SOURCES, DELTA_A])
    const second = fakeFetch([DELTA_B, USAGE, DONE])

    const fetchImpl = ((url: string, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      calls.push({ url, lastEventId: headers.get('Last-Event-ID') })
      return calls.length === 1 ? first(url, init) : second(url, init)
    }) as unknown as typeof fetch

    const deltas: string[] = []
    const result = await streamRun('/stream', {
      fetchImpl,
      maxResumes: 1,
      resumeUrl: (runId) => `/runs/${runId}/stream`,
      handlers: { onDelta: (text) => deltas.push(text) },
    })

    expect(calls).toEqual([
      { url: '/stream', lastEventId: null },
      { url: '/runs/run-1/stream', lastEventId: '2' },
    ])
    expect(deltas).toEqual(['答', '案'])
    expect(result.outcome).toBe('done')
  })

  it('续传次数用尽后仍然失败，不会无限重连', async () => {
    const fetchImpl = vi.fn(fakeFetch([META, DELTA_A])) as unknown as typeof fetch

    await expect(
      streamRun('/stream', {
        fetchImpl,
        maxResumes: 2,
        resumeUrl: (runId) => `/runs/${runId}/stream`,
      }),
    ).rejects.toBeInstanceOf(StreamAbortedError)
    expect(fetchImpl).toHaveBeenCalledTimes(3)
  })
})

describe('streamRun malformed event', () => {
  it('坏 JSON 被计数且不影响后续事件', async () => {
    const malformed: MalformedEvent[] = []
    const deltas: string[] = []

    const result = await streamRun('/stream', {
      fetchImpl: fakeFetch([META, frame(1, 'delta', '{不是 JSON'), DELTA_A, DONE]),
      handlers: {
        onDelta: (text) => deltas.push(text),
        onMalformed: (event) => malformed.push(event),
      },
    })

    expect(malformed).toEqual([{ reason: 'bad_json', raw: '{不是 JSON' }])
    expect(deltas).toEqual(['答'])
    expect(result.outcome).toBe('done')
    expect(result.malformedCount).toBe(1)
  })

  it('未知事件名被计数而不是静默丢弃', async () => {
    const malformed: MalformedEvent[] = []

    const result = await streamRun('/stream', {
      fetchImpl: fakeFetch([META, frame(1, 'telemetry', '{}'), DONE]),
      handlers: { onMalformed: (event) => malformed.push(event) },
    })

    expect(malformed[0].reason).toBe('unknown_event')
    expect(result.malformedCount).toBe(1)
  })

  it('缺少 id 的事件被计数，不会污染 seq 校验', async () => {
    const malformed: MalformedEvent[] = []
    const gaps: number[] = []

    await streamRun('/stream', {
      fetchImpl: fakeFetch([
        META,
        frame(null, 'delta', '"无 id"'),
        frame(1, 'delta', '"有 id"'),
        frame(2, 'done', '{}'),
      ]),
      handlers: {
        onMalformed: (event) => malformed.push(event),
        onGap: (expected) => gaps.push(expected),
      },
    })

    expect(malformed[0].reason).toBe('bad_id')
    expect(gaps).toEqual([])
  })
})

describe('streamRun 请求取消', () => {
  it('取消后返回 cancelled 而不是抛错', async () => {
    const controller = new AbortController()
    const promise = streamRun('/stream', {
      fetchImpl: fakeFetch([META, DELTA_A, DELTA_B, DONE], { delayMs: 5 }),
      signal: controller.signal,
      handlers: { onDelta: () => controller.abort() },
    })

    const result = await promise
    expect(result.outcome).toBe('cancelled')
  })

  it('发起前就已取消同样是 cancelled', async () => {
    const controller = new AbortController()
    controller.abort()

    const result = await streamRun('/stream', {
      fetchImpl: (() => Promise.reject(new DOMException('Aborted', 'AbortError'))) as unknown as typeof fetch,
      signal: controller.signal,
    })

    expect(result.outcome).toBe('cancelled')
  })

  it('取消不会被误判成断流失败', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(
      streamRun('/stream', {
        fetchImpl: (() => Promise.reject(new DOMException('Aborted', 'AbortError'))) as unknown as typeof fetch,
        signal: controller.signal,
      }),
    ).resolves.toMatchObject({ outcome: 'cancelled' })
  })
})

describe('streamRun 服务端错误', () => {
  it('error 事件转成带错误码的异常', async () => {
    const promise = streamRun('/stream', {
      fetchImpl: fakeFetch([META, frame(1, 'error', JSON.stringify({ code: 'upstream_error', message: '模型网关 502' }))]),
    })

    await expect(promise).rejects.toBeInstanceOf(StreamServerError)
    await expect(promise).rejects.toMatchObject({ code: 'upstream_error', message: '模型网关 502' })
  })

  it('HTTP 非 2xx 抛 ApiError 并带上响应正文', async () => {
    const promise = streamRun('/stream', {
      fetchImpl: fakeFetch([], { status: 503, body: '事件日志暂不可用' }),
    })

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({ status: 503, body: '事件日志暂不可用' })
  })
})
