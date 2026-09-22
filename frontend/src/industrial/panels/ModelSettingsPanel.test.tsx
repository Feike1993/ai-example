import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelSettingsPanel } from './ModelSettingsPanel'

const settings = {
  providers: [{
    id: 'dashscope',
    label: '阿里云百炼',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen3.8-max',
    models: ['qwen3.8-max', 'qwen-vl-max'],
    capabilities: ['chat', 'vision'],
    keyConfigured: true,
  }],
  routes: [
    { capability: 'chat', providerId: 'dashscope', model: 'qwen3.8-max', voice: null },
    { capability: 'vision', providerId: 'dashscope', model: 'qwen-vl-max', voice: null },
  ],
}

function renderPanel() {
  return render(<MantineProvider forceColorScheme="light"><ModelSettingsPanel /></MantineProvider>)
}

describe('ModelSettingsPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('编辑 Provider 时可继续添加多个模型并按数组保存', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init })
      if (init?.method === 'PUT') return new Response(null, { status: 200 })
      return new Response(JSON.stringify(settings), { status: 200 })
    }))
    renderPanel()

    await screen.findByRole('heading', { name: 'Provider 管理' })
    await userEvent.click(screen.getByRole('button', { name: '编辑' }))
    const dialog = await screen.findByRole('dialog')
    const modelInput = within(dialog).getByRole('combobox', { name: /可用模型/ })
    await userEvent.type(modelInput, 'qwen-plus{Enter}')
    await userEvent.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => expect(calls.some((call) => call.init?.method === 'PUT')).toBe(true))
    const saved = calls.find((call) => call.init?.method === 'PUT')
    expect(JSON.parse(String(saved?.init?.body))).toMatchObject({
      models: ['qwen3.8-max', 'qwen-vl-max', 'qwen-plus'],
    })
  })

  it('能力路由可从同一 Provider 的多个模型中选择一个', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), init })
      if (init?.method === 'PUT') return new Response(null, { status: 200 })
      return new Response(JSON.stringify(settings), { status: 200 })
    }))
    renderPanel()

    const modelSelect = await screen.findByRole('combobox', { name: '默认聊天模型模型' })
    await userEvent.click(modelSelect)
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(modelSelect).toHaveValue('qwen-vl-max')
    await userEvent.click(screen.getAllByRole('button', { name: '保存配置' })[0])

    await waitFor(() => expect(calls.some((call) => call.url.includes('/routes/chat') && call.init?.method === 'PUT')).toBe(true))
    const saved = calls.find((call) => call.url.includes('/routes/chat') && call.init?.method === 'PUT')
    expect(JSON.parse(String(saved?.init?.body))).toMatchObject({ providerId: 'dashscope', model: 'qwen-vl-max' })
  })
})
