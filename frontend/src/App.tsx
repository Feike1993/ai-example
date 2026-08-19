import { AppShell, NavLink, Stack, Text } from '@mantine/core'
import { useState } from 'react'
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

/**
 * 样例 playground 壳：侧栏品牌 + 四个接口面板。
 */
function App() {
  const [sample, setSample] = useState<SampleId>('chat')

  return (
    <AppShell
      navbar={{ width: 268, breakpoint: 'sm' }}
      padding="lg"
      classNames={{ navbar: 'app-navbar', main: 'app-main' }}
    >
      <AppShell.Navbar p="lg">
        <Stack gap={4} mb="xl">
          <div className="wordmark">ai-example</div>
          <Text size="sm" c="dimmed">
            接口 playground
          </Text>
        </Stack>
        <Stack gap={4}>
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
      </AppShell.Navbar>
      <AppShell.Main>
        {sample === 'chat' && <ChatPanel />}
        {sample === 'structured' && <StructuredPanel />}
        {sample === 'tools' && <ToolsPanel />}
        {sample === 'agent' && <AgentPanel />}
      </AppShell.Main>
    </AppShell>
  )
}

export default App
