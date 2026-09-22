import { AppShell, Modal, NavLink, SegmentedControl, Stack, Text } from '@mantine/core'
import { useEffect, useMemo, useState } from 'react'
import { EvalPanel } from './panels/EvalPanel'
import { HybridRagPanel } from './panels/HybridRagPanel'
import { HydePanel } from './panels/HydePanel'
import { SemanticChunkPanel } from './panels/SemanticChunkPanel'
import { ParentChildPanel } from './panels/ParentChildPanel'
import { AgentPanel } from './panels/AgentPanel'
import { ChatPanel } from './panels/ChatPanel'
import { ContextPanel } from './panels/ContextPanel'
import { McpPanel } from './panels/McpPanel'
import { MemoryPanel } from './panels/MemoryPanel'
import { MemoryExtractPanel } from './panels/MemoryExtractPanel'
import { MemoryComparePanel } from './panels/MemoryComparePanel'
import { GuardrailPanel } from './panels/GuardrailPanel'
import { MemoryInformedRagPanel } from './panels/MemoryInformedRagPanel'
import { RagMemoryComparePanel } from './panels/RagMemoryComparePanel'
import {
  mcpBearerGuide,
  agentToolSseGuide,
  agentUsageGuide,
  guardrailGuide,
  ragCitationGuide,
} from './guides'
import { MultiAgentPanel } from './panels/MultiAgentPanel'
import { RagPanel } from './panels/RagPanel'
import { StructuredPanel } from './panels/StructuredPanel'
import { ToolsPanel } from './panels/ToolsPanel'
import { SceneToolbar } from './shared/SceneToolbar'
import { clearAuth, getAccessToken, getProductionUser, UNAUTHORIZED_EVENT } from './industrial/lib/auth'
import { LoginForm } from './industrial/panels/SecurityPanel'
import { stageHints, stageLabels, type LearningStage } from './shared/brand'
import {
  advancedSamples,
  baselineSamples,
  groupAdvancedSamples,
  type AdvancedSampleMeta,
  type BaselineSampleMeta,
  type PlaygroundSampleId,
} from './shared/samples'

function sampleStage(id: PlaygroundSampleId): LearningStage {
  return advancedSamples.some((item) => item.id === id) ? 'advanced' : 'baseline'
}

/**
 * 样例 playground 壳：侧栏课程导航、统一登录入口与全局模型设置入口。
 */
function App() {
  const [sample, setSample] = useState<PlaygroundSampleId>('chat')
  const provider = 'deepseek'
  const [authed, setAuthed] = useState(() => Boolean(getAccessToken()))
  const [loginOpened, setLoginOpened] = useState(false)

  const stage = sampleStage(sample)
  const advancedGroups = useMemo(() => groupAdvancedSamples(), [])

  useEffect(() => {
    const onUnauthorized = () => setAuthed(false)
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  const onStageChange = (value: string) => {
    const next = value as LearningStage
    if (next === stage) {
      return
    }
    const list = next === 'baseline' ? baselineSamples : advancedSamples
    setSample(list[0].id)
  }

  const onLogout = () => { clearAuth(); setAuthed(false) }

  return (
    <AppShell
      navbar={{ width: 248, breakpoint: 'sm' }}
      padding="lg"
      classNames={{ navbar: 'app-navbar', main: 'app-main' }}
    >
      <AppShell.Navbar p="md" className="app-navbar-inner">
        <div className="app-navbar-body">
          <Stack gap={2} mb="md">
            <div className="wordmark">
              <span className="wordmark-mark">AI</span>
              <span className="wordmark-rest">Example</span>
            </div>
            <Text size="xs" c="dimmed">
              接口 playground
            </Text>
          </Stack>
          <SegmentedControl
            className="nav-stage-switch"
            fullWidth
            size="xs"
            value={stage}
            onChange={onStageChange}
            data={[
              { value: 'baseline', label: stageLabels.baseline },
              { value: 'advanced', label: stageLabels.advanced },
            ]}
          />
          <Text size="xs" c="dimmed" mb="xs" className="nav-stage-hint">
            {stageHints[stage]}
          </Text>
          <Stack gap={2} mb="md">
            {stage === 'baseline'
              ? baselineSamples.map((item) => (
                  <CompactNavItem
                    key={item.id}
                    item={item}
                    active={sample === item.id}
                    advanced={false}
                    onSelect={() => setSample(item.id)}
                  />
                ))
              : advancedGroups.map(({ group, items }) => (
                  <Stack key={group.id} gap={2} className="nav-group">
                    <Text className="nav-group-label">{group.label}</Text>
                    {items.map((item) => (
                      <CompactNavItem
                        key={item.id}
                        item={item}
                        active={sample === item.id}
                        advanced
                        onSelect={() => setSample(item.id)}
                      />
                    ))}
                  </Stack>
                ))}
          </Stack>
        </div>
        <Stack gap={4}>
          <a className="promo-entry" href="/promo.html">
            宣传页 · 8 样例路径 →
          </a>
        </Stack>
      </AppShell.Navbar>
      <AppShell.Main>
        <SceneToolbar scene="learning" user={authed ? getProductionUser() : null} onLogin={() => setLoginOpened(true)} onLogout={onLogout} />
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
        {sample === 'memory' && <MemoryPanel provider={provider} />}
        {sample === 'hyde' && <HydePanel provider={provider} />}
        {sample === 'semanticChunk' && <SemanticChunkPanel provider={provider} />}
        {sample === 'parentChild' && <ParentChildPanel provider={provider} />}
        {sample === 'memoryExtract' && <MemoryExtractPanel provider={provider} />}
        {sample === 'memoryCompare' && <MemoryComparePanel provider={provider} />}
        {sample === 'mcpBearer' && (
          <McpPanel provider={provider} guide={mcpBearerGuide} title="MCP Bearer" />
        )}
        {sample === 'agentToolSse' && (
          <AgentPanel
            key="agentToolSse"
            provider={provider}
            guide={agentToolSseGuide}
            title="Agent 逐步 SSE"
            defaultTransport="sse"
            focus="sse"
          />
        )}
        {sample === 'agentUsage' && (
          <AgentPanel
            key="agentUsage"
            provider={provider}
            guide={agentUsageGuide}
            title="Usage 累加"
            defaultTransport="sync"
            focus="usage"
          />
        )}
        {sample === 'guardrail' && <GuardrailPanel provider={provider} guide={guardrailGuide} />}
        {sample === 'ragCitation' && (
          <RagPanel
            provider={provider}
            guide={ragCitationGuide}
            title="RAG Citation"
            hint="citationMode=required：结构化引用并校验 sourceId。SSE 仍为自由文本。"
            defaultCitationMode="required"
            allowSse={false}
          />
        )}
        {sample === 'memoryInformedRag' && <MemoryInformedRagPanel provider={provider} />}
        {sample === 'ragMemoryCompare' && <RagMemoryComparePanel provider={provider} />}
      </AppShell.Main>
      <Modal opened={loginOpened} onClose={() => setLoginOpened(false)} title="统一登录">
        <LoginForm title="平台登录" subtitle="登录后可进入全局模型与服务设置；教学样例仍可匿名使用。演示密码均为 demo。" onLoggedIn={() => { setAuthed(true); setLoginOpened(false) }} />
      </Modal>
    </AppShell>
  )
}

function CompactNavItem({
  item,
  active,
  advanced,
  onSelect,
}: {
  item: BaselineSampleMeta | AdvancedSampleMeta
  active: boolean
  advanced: boolean
  onSelect: () => void
}) {
  return (
    <NavLink
      className={`nav-item nav-item-compact${advanced ? ' nav-item--advanced' : ''}`}
      label={
        <span className="nav-item-row">
          <span className="nav-item-index">{String(item.index).padStart(2, '0')}</span>
          <span className="nav-item-label">{item.label}</span>
        </span>
      }
      active={active}
      onClick={onSelect}
    />
  )
}

export default App
