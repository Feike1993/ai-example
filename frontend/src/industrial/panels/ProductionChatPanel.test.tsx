import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeFetch, frame } from '../lib/testing/sseFixtures'
import { ProductionChatPanel } from './ProductionChatPanel'

function renderPanel(ui: ReactElement) {
  return render(<MantineProvider forceColorScheme="light">{ui}</MantineProvider>)
}

const META = frame(0, 'meta', JSON.stringify({ runId: 'run-1' }))
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
const DONE = frame(4, 'done', JSON.stringify({ retrievalEmpty: false }))

describe('ProductionChatPanel', () => {
  beforeEach(() => {
    // listProviders 之外的请求由各用例自行覆盖
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
})
