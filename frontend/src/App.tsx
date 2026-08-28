import { AppShell, NavLink, Select, Stack, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { listProviders, type ProviderView } from './api'
import { EvalPanel } from './panels/EvalPanel'
import { HybridRagPanel } from './panels/HybridRagPanel'
import { AgentPanel } from './panels/AgentPanel'
import { ChatPanel } from './panels/ChatPanel'
import { ContextPanel } from './panels/ContextPanel'
import { McpPanel } from './panels/McpPanel'
import { MultiAgentPanel } from './panels/MultiAgentPanel'
import { RagPanel } from './panels/RagPanel'
import { StructuredPanel } from './panels/StructuredPanel'
import { ToolsPanel } from './panels/ToolsPanel'
import { stageLabels } from './shared/brand'
import { advancedSamples, baselineSamples, type PlaygroundSampleId } from './shared/samples'

const PROVIDER_STORAGE_KEY = 'ai-example.provider'

/**
 * 样例 playground 壳：侧栏品牌、模型选择、各期接口面板。
 */
function App() {
  const [sample, setSample] = useState<PlaygroundSampleId>('chat')
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
      <AppShell.Navbar p="lg" className="app-navbar-inner">
        <div className="app-navbar-body">
          <Stack gap={4} mb="xl">
            <div className="wordmark">
              <span className="wordmark-mark">AI</span>
              <span className="wordmark-rest">Example</span>
            </div>
            <Text size="sm" c="dimmed">
              接口 playground
            </Text>
          </Stack>
          <Text className="nav-section-label">{stageLabels.baseline}</Text>
          <Stack gap={4} mb="md">
            {baselineSamples.map((item) => (
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
          <Text className="nav-section-label nav-section-label--advanced">{stageLabels.advanced}</Text>
          <Stack gap={4} mb="lg">
            {advancedSamples.map((item) => (
              <NavLink
                key={item.id}
                className="nav-item nav-item--advanced"
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
        </div>
        <a className="promo-entry" href="/promo.html">
          <span className="promo-entry-label">基础闭环宣传页</span>
          <span className="promo-entry-desc">8 样例学习路径</span>
        </a>
      </AppShell.Navbar>
      <AppShell.Main>
        {sample === 'chat' && <ChatPanel provider={provider} />}
        {sample === 'structured' && <StructuredPanel provider={provider} />}
        {sample === 'tools' && <ToolsPanel provider={provider} />}
        {sample === 'agent' && <AgentPanel provider={provider} />}
        {sample === 'mcp' && <McpPanel provider={provider} />}
        {sample === 'rag' && <RagPanel provider={provider} />}
        {sample === 'context' && <ContextPanel provider={provider} />}
        {sample === 'multiagent' && <MultiAgentPanel provider={provider} />}
        {sample === 'hybridRag' && <HybridRagPanel provider={provider} />}
        {sample === 'eval' && <EvalPanel provider={provider} />}
      </AppShell.Main>
    </AppShell>
  )
}

export default App
