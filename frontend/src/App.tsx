import { AppShell, NavLink, SegmentedControl, Select, Stack, Text } from '@mantine/core'
import { useEffect, useMemo, useState } from 'react'
import { listProviders, type ProviderView } from './api'
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
import { stageHints, stageLabels, type LearningStage } from './shared/brand'
import {
  advancedSamples,
  baselineSamples,
  type AdvancedSampleMeta,
  type BaselineSampleMeta,
  type PlaygroundSampleId,
} from './shared/samples'

const PROVIDER_STORAGE_KEY = 'ai-example.provider'

function sampleStage(id: PlaygroundSampleId): LearningStage {
  return advancedSamples.some((item) => item.id === id) ? 'advanced' : 'baseline'
}

/**
 * 样例 playground 壳：侧栏品牌、模型选择、各期接口面板。
 */
function App() {
  const [sample, setSample] = useState<PlaygroundSampleId>('chat')
  const [provider, setProvider] = useState('deepseek')
  const [providers, setProviders] = useState<ProviderView[]>([])

  const stage = sampleStage(sample)
  const visibleSamples = useMemo(
    () => (stage === 'baseline' ? baselineSamples : advancedSamples),
    [stage],
  )

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

  const onStageChange = (value: string) => {
    const next = value as LearningStage
    if (next === stage) {
      return
    }
    const list = next === 'baseline' ? baselineSamples : advancedSamples
    setSample(list[0].id)
  }

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
            {visibleSamples.map((item) => (
              <CompactNavItem
                key={item.id}
                item={item}
                active={sample === item.id}
                advanced={stage === 'advanced'}
                onSelect={() => setSample(item.id)}
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
        <a className="promo-entry" href="/promo.html">
          宣传页 · 8 样例路径 →
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
