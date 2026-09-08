import { AppShell, NavLink, Select, Stack, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { listProviders, type ProviderView } from '../api'
import { ProductionChatPanel } from './panels/ProductionChatPanel'
import { industrialSections, type IndustrialSectionId } from './sections'

const PROVIDER_STORAGE_KEY = 'ai-example.provider'

/**
 * 工业级区外壳。
 *
 * 刻意不复用样例场的 App：那边的导航是「按课程期数排列的 23 个样例」，
 * 这边是「一条生产链路的若干能力面」，两种信息结构强行合并只会让两边都别扭。
 * 复用发生在更低的层次——组件、SSE 客户端、Provider 列表接口。
 */
export function IndustrialApp() {
  const [section, setSection] = useState<IndustrialSectionId>('chat')
  const [provider, setProvider] = useState('deepseek')
  const [providers, setProviders] = useState<ProviderView[]>([])

  useEffect(() => {
    void listProviders()
      .then((data) => {
        setProviders(data.providers)
        const stored = localStorage.getItem(PROVIDER_STORAGE_KEY)
        const allowed = new Set(data.providers.map((item) => item.id))
        setProvider(stored && allowed.has(stored) ? stored : data.defaultProvider || 'deepseek')
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
      navbar={{ width: 248, breakpoint: 'sm' }}
      padding="lg"
      classNames={{ navbar: 'industrial-navbar', main: 'industrial-main' }}
    >
      <AppShell.Navbar p="md" className="industrial-navbar-inner">
        <div className="industrial-navbar-body">
          <Stack gap={2} mb="md">
            <div className="industrial-wordmark">
              <span className="industrial-wordmark-mark">PROD</span>
              <span className="industrial-wordmark-rest">工业级</span>
            </div>
            <Text size="xs" c="dimmed">
              /api/v1 生产链路
            </Text>
          </Stack>
          <Stack gap={2} mb="md">
            {industrialSections.map((item) => (
              <NavLink
                key={item.id}
                className="industrial-nav-item"
                label={item.label}
                description={item.description}
                active={section === item.id}
                disabled={!item.ready}
                onClick={() => setSection(item.id)}
              />
            ))}
          </Stack>
          <Select
            label="模型"
            size="xs"
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
        <a className="industrial-back" href="/index.html">
          ← 回到教学样例场
        </a>
      </AppShell.Navbar>
      <AppShell.Main>
        {section === 'chat' && <ProductionChatPanel provider={provider} />}
      </AppShell.Main>
    </AppShell>
  )
}
