import { AppShell, NavLink, Select, Stack, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { listProviders, type ProviderView } from './api'
import { AgentPanel } from './panels/AgentPanel'
import { ChatPanel } from './panels/ChatPanel'
import { StructuredPanel } from './panels/StructuredPanel'
import { ToolsPanel } from './panels/ToolsPanel'

const samples = [
  { id: 'chat', label: 'Chat', description: 'Token / SSE / TTFT' },
  { id: 'structured', label: '结构化输出', description: '工单 JSON' },
  { id: 'tools', label: 'Tool Calling', description: '天气 + 计算器' },
  { id: 'agent', label: 'Agent Loop', description: 'ReAct / Framework' },
] as const

type SampleId = (typeof samples)[number]['id']

const PROVIDER_STORAGE_KEY = 'ai-example.provider'

/**
 * 样例 playground 壳：侧栏品牌、模型选择、四个接口面板。
 */
function App() {
  const [sample, setSample] = useState<SampleId>('chat')
  const [provider, setProvider] = useState('deepseek')
  const [providers, setProviders] = useState<ProviderView[]>([])

  useEffect(() => {
    void listProviders()
      .then((data) => {
        setProviders(data.providers)
        const stored = localStorage.getItem(PROVIDER_STORAGE_KEY)
        const allowed = new Set(data.providers.map((item) => item.id))
        if (stored && allowed.has(stored)) {
          setProvider(stored)
          return
        }
        setProvider(data.defaultProvider || 'deepseek')
      })
      .catch(() => {
        setProviders([
          { id: 'deepseek', label: 'DeepSeek', model: 'deepseek-v4-flash', configured: false },
        ])
      })
  }, [])

  const onProviderChange = (value: string | null) => {
    if (!value) {
      return
    }
    setProvider(value)
    localStorage.setItem(PROVIDER_STORAGE_KEY, value)
  }

  const selected = providers.find((item) => item.id === provider)

  return (
    <AppShell
      navbar={{ width: 280, breakpoint: 'sm' }}
      padding="lg"
      classNames={{ navbar: 'app-navbar', main: 'app-main' }}
    >
      <AppShell.Navbar p="lg">
        <Stack gap={4} mb="xl">
          <div className="wordmark">
            <span className="wordmark-mark">AI</span>
            <span className="wordmark-rest">Example</span>
          </div>
          <Text size="sm" c="dimmed">
            接口 playground
          </Text>
        </Stack>
        <Stack gap={4} mb="lg">
          {samples.map((item) => (
            <NavLink
              key={item.id}
              className="nav-item"
              label={item.label}
              description={item.description}
              active={sample === item.id}
              onClick={() => setSample(item.id)}
            />
          ))}
        </Stack>
        <Select
          label="模型"
          description={selected ? selected.model : '默认 DeepSeek'}
          data={providers.map((item) => ({
            value: item.id,
            label: item.configured ? item.label : `${item.label}（未配 Key）`,
          }))}
          value={provider}
          onChange={onProviderChange}
          allowDeselect={false}
        />
      </AppShell.Navbar>
      <AppShell.Main>
        {sample === 'chat' && <ChatPanel provider={provider} />}
        {sample === 'structured' && <StructuredPanel provider={provider} />}
        {sample === 'tools' && <ToolsPanel provider={provider} />}
        {sample === 'agent' && <AgentPanel provider={provider} />}
      </AppShell.Main>
    </AppShell>
  )
}

export default App
