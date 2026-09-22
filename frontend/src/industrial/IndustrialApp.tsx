import { AppShell, NavLink, Stack, Text } from '@mantine/core'
import { useEffect, useState } from 'react'
import { SceneToolbar } from '../shared/SceneToolbar'
import { clearAuth, getAccessToken, getProductionUser, UNAUTHORIZED_EVENT } from './lib/auth'
import { ObservabilityPanel } from './panels/ObservabilityPanel'
import { ProductionChatPanel } from './panels/ProductionChatPanel'
import { LoginForm, SecurityPanel } from './panels/SecurityPanel'
import { ModelSettingsPanel } from './panels/ModelSettingsPanel'
import { industrialSections, type IndustrialSectionId } from './sections'

/**
 * 工业级区外壳。
 *
 * 刻意不复用样例场的 App：那边的导航是「按课程期数排列的 23 个样例」，
 * 这边是「一条生产链路的若干能力面」，两种信息结构强行合并只会让两边都别扭。
 * 复用发生在更低的层次——组件、SSE 客户端、Provider 列表接口。
 */
export function IndustrialApp() {
  const [section, setSection] = useState<IndustrialSectionId>(() => new URLSearchParams(window.location.search).get('section') === 'modelSettings' ? 'modelSettings' : 'chat')
  const [authed, setAuthed] = useState(() => Boolean(getAccessToken()))
  const user = getProductionUser()

  useEffect(() => {
    const onUnauthorized = () => setAuthed(false)
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  const onLogout = () => {
    clearAuth()
    setAuthed(false)
  }

  if (!authed) {
    return (
      <AppShell padding="lg">
        <AppShell.Main>
          <LoginForm onLoggedIn={() => setAuthed(true)} />
        </AppShell.Main>
      </AppShell>
    )
  }

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
                data-testid={`nav-${item.id}`}
                label={item.label}
                description={item.description}
                active={section === item.id}
                disabled={!item.ready}
                onClick={() => setSection(item.id)}
              />
            ))}
          </Stack>
        </div>
      </AppShell.Navbar>
      <AppShell.Main>
        <SceneToolbar scene="industrial" user={user} onLogin={() => undefined} onLogout={onLogout} />
        {section === 'chat' && <ProductionChatPanel provider="deepseek" />}
        {section === 'modelSettings' && <ModelSettingsPanel />}
        {section === 'security' && <SecurityPanel onLogout={onLogout} />}
        {section === 'observability' && <ObservabilityPanel />}
      </AppShell.Main>
    </AppShell>
  )
}
