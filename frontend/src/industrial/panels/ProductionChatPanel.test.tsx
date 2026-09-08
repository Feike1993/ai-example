import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeFetch, frame, sseResponse } from '../lib/testing/sseFixtures'
import { ProductionChatPanel } from './ProductionChatPanel'

function renderPanel(ui: ReactElement) {
  return render(<MantineProvider forceColorScheme="light">{ui}</MantineProvider>)
}

const META = frame(0, 'meta', JSON.stringify({ runId: 'run-1', sessionId: 's-1' }))
const SOURCES = frame(
  1,
  'sources',
  JSON.stringify({
    sources: [{ id: 'd1', source: '03-rag.md', excerpt: 'RAG 是检索增强生成。' }],
    retrievalEmpty: false,
    retrievalMode: 'hybrid',
  }),
)
const DELTA = frame(2, 'delta', JSON.stringify('这是答案。'))
const USAGE = frame(3, 'usage', JSON.stringify({ answerChars: 5, sourceCount: 1 }))
const DONE = frame(4, 'done', JSON.stringify({ retrievalEmpty: false, sessionId: 's-1', persisted: true }))

/** 依次吐出多条流，并记录每次请求的 URL，便于断言 sessionId 有没有回传。 */
function scriptedFetch(streams: string[][]) {
  const calls: string[] = []
  let turn = 0
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    calls.push(`${init?.method ?? 'GET'} ${url}`)
    if (url.includes('/sessions/')) {
      return new Response(
        JSON.stringify(init?.method === 'DELETE'
          ? { sessionId: 's-1', existed: true }
          : { sessionId: 's-1', messages: [] }),
        { status: 200 },
      )
    }
    const chunks = streams[Math.min(turn, streams.length - 1)]
    turn += 1
    return sseResponse(chunks, init?.signal)
  }) as unknown as typeof fetch
  return { impl, calls }
}

describe('ProductionChatPanel', () => {
  beforeEach(() => {
    // 会话 id 存在 localStorage，用例之间必须隔离，否则后跑的用例会带上前一个的会话
    localStorage.clear()
    vi.stubGlobal('fetch', fakeFetch([]))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('完整流展示答案、来源与已完成状态', async () => {
    vi.stubGlobal('fetch', fakeFetch([META, SOURCES, DELTA, USAGE, DONE]))
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument())
    expect(screen.getByText('这是答案。')).toBeInTheDocument()
    expect(screen.getByText('RAG 是检索增强生成。')).toBeInTheDocument()
    expect(screen.getByText('hybrid')).toBeInTheDocument()
  })

  it('中途断流标注结果不完整，而不是当成回答完毕', async () => {
    vi.stubGlobal('fetch', fakeFetch([META, SOURCES, DELTA]))
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText('请求失败')).toBeInTheDocument())
    expect(screen.getByText(/未收到 done 事件/)).toBeInTheDocument()
  })

  it('HTTP 错误展示为红色告警并带上后端正文', async () => {
    vi.stubGlobal('fetch', fakeFetch([], { status: 503, body: '事件日志暂不可用（Redis 连接异常）' }))
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText('请求失败')).toBeInTheDocument())
    expect(screen.getByText(/HTTP 503/)).toBeInTheDocument()
    expect(screen.getByText(/Redis 连接异常/)).toBeInTheDocument()
  })

  it('服务端 error 事件展示错误码', async () => {
    vi.stubGlobal(
      'fetch',
      fakeFetch([META, frame(1, 'error', JSON.stringify({ code: 'stream_timeout', message: '生成超时，请重试' }))]),
    )
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText(/stream_timeout/)).toBeInTheDocument())
  })

  it('点击停止会取消请求，不弹错误', async () => {
    vi.stubGlobal('fetch', fakeFetch([META, SOURCES, DELTA, USAGE, DONE], { delayMs: 40 }))
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '停止' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: '停止' }))

    await waitFor(() => expect(screen.getByText('已取消')).toBeInTheDocument())
    expect(screen.queryByText('请求失败')).toBeNull()
  })

  it('sessionId 从 meta 取出并在下一轮回传', async () => {
    const second = [
      frame(0, 'meta', JSON.stringify({ runId: 'run-2', sessionId: 's-1' })),
      frame(1, 'sources', JSON.stringify({ sources: [], retrievalEmpty: false, retrievalMode: 'hybrid' })),
      frame(2, 'delta', JSON.stringify('第二轮回答。')),
      frame(3, 'done', JSON.stringify({ sessionId: 's-1', persisted: true })),
    ]
    const { impl, calls } = scriptedFetch([[META, SOURCES, DELTA, USAGE, DONE], second])
    vi.stubGlobal('fetch', impl)
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument())

    // 首轮不带 sessionId，由后端新建
    expect(calls[0]).not.toContain('sessionId=')

    await userEvent.type(screen.getByLabelText('问题'), '追问')
    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByText('第二轮回答。')).toBeInTheDocument())

    const streamCalls = calls.filter((call) => call.includes('/chat/stream'))
    expect(streamCalls).toHaveLength(2)
    expect(streamCalls[1]).toContain('sessionId=s-1')
    expect(localStorage.getItem('ai-example.production.sessionId')).toBe('s-1')
  })

  it('多轮问答累积展示，旧轮次不被新回答覆盖', async () => {
    const second = [
      frame(0, 'meta', JSON.stringify({ runId: 'run-2', sessionId: 's-1' })),
      frame(1, 'delta', JSON.stringify('第二轮回答。')),
      frame(2, 'done', JSON.stringify({ sessionId: 's-1', persisted: true })),
    ]
    const { impl } = scriptedFetch([[META, SOURCES, DELTA, USAGE, DONE], second])
    vi.stubGlobal('fetch', impl)
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByText('这是答案。')).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText('问题'), '追问')
    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByText('第二轮回答。')).toBeInTheDocument())

    expect(screen.getByText('这是答案。')).toBeInTheDocument()
    expect(screen.getByText('追问')).toBeInTheDocument()
    expect(screen.getByText(/已存 2 轮/)).toBeInTheDocument()
  })

  it('清空会话调用 DELETE 并清掉本地历史与 sessionId', async () => {
    const { impl, calls } = scriptedFetch([[META, SOURCES, DELTA, USAGE, DONE]])
    vi.stubGlobal('fetch', impl)
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))
    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: '清空会话' }))

    await waitFor(() => expect(screen.queryByText('这是答案。')).toBeNull())
    expect(calls.some((call) => call.startsWith('DELETE') && call.includes('/sessions/s-1'))).toBe(true)
    expect(localStorage.getItem('ai-example.production.sessionId')).toBeNull()
    expect(screen.getByText(/会话 未建立/)).toBeInTheDocument()
  })

  it('session_busy 提示会话正忙，而不是笼统的生成失败', async () => {
    vi.stubGlobal(
      'fetch',
      fakeFetch([
        META,
        frame(1, 'error', JSON.stringify({
          code: 'session_busy',
          message: '该会话已有一轮对话正在进行，请等待其完成后再试',
        })),
      ]),
    )
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText('会话正忙')).toBeInTheDocument())
    expect(screen.getByText(/另起一段/)).toBeInTheDocument()
  })

  it('后端未能落库时明确标注本轮不计入历史', async () => {
    vi.stubGlobal(
      'fetch',
      fakeFetch([
        META,
        SOURCES,
        DELTA,
        USAGE,
        frame(4, 'done', JSON.stringify({ sessionId: 's-1', persisted: false })),
      ]),
    )
    renderPanel(<ProductionChatPanel provider="deepseek" />)

    await userEvent.click(screen.getByRole('button', { name: '流式提问' }))

    await waitFor(() => expect(screen.getByText('本轮未计入历史')).toBeInTheDocument())
    // 回答本身仍要展示：生成是成功的，只是没存下来
    expect(screen.getByText('这是答案。')).toBeInTheDocument()
  })
})
