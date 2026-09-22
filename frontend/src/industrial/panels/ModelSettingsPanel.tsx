import { Alert, Badge, Button, Checkbox, Group, Modal, MultiSelect, NumberInput, Paper, PasswordInput, Select, SimpleGrid, Stack, Table, TagsInput, Text, TextInput, Title } from '@mantine/core'
import { useEffect, useMemo, useState } from 'react'
import { getModelSettings, putGlobalProvider, putModelRoute, type GlobalProvider, type ModelRoute, type ModelSettings } from '../lib/productionApi'

const labels: Record<string, string> = { chat: '默认聊天模型', vision: '默认视觉模型', embedding: '默认向量模型', asr: '语音识别 ASR', tts: '语音合成 TTS' }
const capabilityMeta: Record<string, { badge: string; description: string }> = {
  chat: { badge: '文本对话', description: '用于常规对话、内容生成和工具调用。' },
  vision: { badge: '图像理解', description: '用于图片理解、图片描述与 PDF 文档解析。' },
  embedding: { badge: '向量检索', description: '用于知识检索、语义匹配和向量化任务。' },
  asr: { badge: '语音识别', description: '将用户上传的音频转换为可处理的文本。' },
  tts: { badge: '语音合成', description: '将模型生成的文本转换为自然语音。' },
}

const capabilityLabels: Record<string, string> = {
  chat: '文本对话',
  tools: '工具调用',
  vision: '图像理解',
  embedding: '向量检索',
  asr: '语音识别',
  tts: '语音合成',
}

const routeRules = [
  { title: '文本对话', detail: '使用默认聊天模型' },
  { title: '图片与 PDF', detail: '交给默认视觉模型处理' },
  { title: '检索与知识库', detail: '使用默认向量模型' },
  { title: '语音输入', detail: '先经 ASR 转换为文本' },
  { title: '语音回复', detail: '启用后由 TTS 合成语音' },
]

/** 管理员的全局能力路由页；密钥只在 Provider 编辑弹窗中写入，目录页永不回显。 */
export function ModelSettingsPanel() {
  const [settings, setSettings] = useState<ModelSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState<string | null>(null)
  const [providerOpened, setProviderOpened] = useState(false)
  const [editingProvider, setEditingProvider] = useState<GlobalProvider | null>(null)
  useEffect(() => { void getModelSettings().then(setSettings).catch((e: Error) => setError(e.message)) }, [])
  const routes = useMemo(() => new Map(settings?.routes.map((route) => [route.capability, route])), [settings])
  const save = async (capability: string, route: ModelRoute) => {
    setSaving(capability); setError(null)
    try { await putModelRoute(capability, { providerId: route.providerId, model: route.model, voice: route.voice }); setSettings(await getModelSettings()) }
    catch (e) { setError(e instanceof Error ? e.message : '保存失败') } finally { setSaving(null) }
  }
  if (error && !settings) return <Alert color="red">{error}</Alert>
  if (!settings) return <Text c="dimmed">正在加载模型与服务设置…</Text>
  return <Stack gap="lg" className="model-settings-page"><div className="model-settings-heading"><Title order={1}>模型与服务设置</Title><Text c="dimmed">按能力配置默认模型与 Provider，统一管理 AI 服务供应商与模型，满足不同业务场景的需求。</Text></div>
    {error ? <Alert color="red">{error}</Alert> : null}
    <div className="model-settings-overview"><SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} className="model-route-grid">
      {Object.keys(labels).map((capability) => <RouteCard key={capability} capability={capability} label={labels[capability]} route={routes.get(capability)} settings={settings} saving={saving === capability} onSave={save} />)}
    </SimpleGrid><Paper withBorder p="lg" className="route-rule"><Title order={3}>路由规则</Title><Text size="sm" c="dimmed">系统根据输入类型自动选择对应能力。</Text>{routeRules.map((rule, index) => <Group key={rule.title} align="flex-start" wrap="nowrap"><span className="route-number">{index + 1}</span><Stack gap={1}><Text size="sm" fw={700}>{rule.title}</Text><Text size="xs" c="dimmed">{rule.detail}</Text></Stack></Group>)}</Paper></div>
    <Paper withBorder p="lg" className="provider-management"><Group justify="space-between" align="flex-start"><div><Title order={2}>Provider 管理</Title><Text size="sm" c="dimmed">配置和管理各个 AI 服务提供商的连接信息与可用模型。基础地址支持企业内网与自建网关；API Key 只会加密写入。</Text></div><Button className="provider-add" onClick={() => { setEditingProvider(null); setProviderOpened(true) }}>＋ 添加 Provider</Button></Group>
      <Table mt="md"><Table.Thead><Table.Tr><Table.Th>Provider</Table.Th><Table.Th>基础地址</Table.Th><Table.Th>可用模型</Table.Th><Table.Th>能力</Table.Th><Table.Th>密钥</Table.Th><Table.Th>操作</Table.Th></Table.Tr></Table.Thead><Table.Tbody>{settings.providers.map((p) => <Table.Tr key={p.id}><Table.Td>{p.label}</Table.Td><Table.Td>{p.baseUrl}</Table.Td><Table.Td><Group gap={4}>{p.models.map((model) => <Badge key={model} size="xs" variant="outline">{model}</Badge>)}</Group></Table.Td><Table.Td><Group gap={4}>{p.capabilities.map((c) => <Badge key={c} size="xs">{capabilityLabels[c] ?? c}</Badge>)}</Group></Table.Td><Table.Td>{p.keyConfigured ? '已配置' : '未配置'}</Table.Td><Table.Td><Button variant="subtle" size="compact-sm" onClick={() => { setEditingProvider(p); setProviderOpened(true) }}>编辑</Button></Table.Td></Table.Tr>)}</Table.Tbody></Table>
    </Paper>
    <Modal opened={providerOpened} onClose={() => { setProviderOpened(false); setEditingProvider(null) }} title={editingProvider ? `编辑 ${editingProvider.label}` : '添加 Provider'} size="lg" centered overlayProps={{ backgroundOpacity: 0.42, blur: 3 }}>
      {providerOpened ? <ProviderForm key={editingProvider?.id ?? 'new'} initialProvider={editingProvider} onCancel={() => { setProviderOpened(false); setEditingProvider(null) }} onSaved={async () => { setSettings(await getModelSettings()); setProviderOpened(false); setEditingProvider(null) }} /> : null}
    </Modal>
  </Stack>
}

function ProviderForm({ initialProvider, onSaved, onCancel }: { initialProvider: GlobalProvider | null; onSaved: () => Promise<void>; onCancel: () => void }) {
  const editing = initialProvider != null
  const [id, setId] = useState(initialProvider?.id ?? '')
  const [label, setLabel] = useState(initialProvider?.label ?? '')
  const [baseUrl, setBaseUrl] = useState(initialProvider?.baseUrl ?? '')
  const [models, setModels] = useState<string[]>(initialProvider?.models ?? [])
  const [apiKey, setApiKey] = useState('')
  const [capabilities, setCapabilities] = useState<string[]>(initialProvider?.capabilities ?? ['chat'])
  const [temperature, setTemperature] = useState<number | string>(initialProvider?.temperature ?? '')
  const [enableThinking, setEnableThinking] = useState(initialProvider?.enableThinking == null ? 'default' : String(initialProvider.enableThinking))
  const [bypassProxy, setBypassProxy] = useState(initialProvider?.bypassProxy ?? false)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const submit = async () => {
    setSaving(true); setFormError(null)
    try { await putGlobalProvider(id, { label, baseUrl, models, capabilities, temperature: typeof temperature === 'number' ? temperature : undefined, enableThinking: enableThinking === 'default' ? null : enableThinking === 'true', bypassProxy, apiKey: apiKey || undefined }); await onSaved(); setApiKey('') }
    catch (e) { setFormError(e instanceof Error ? e.message : '保存 Provider 失败') } finally { setSaving(false) }
  }
  return <Stack gap="md"><Text size="sm" c="dimmed">连接地址支持企业内网与自建网关。{editing ? 'API Key 留空将保留当前密钥；输入新值则覆盖。' : 'API Key 将加密保存，保存后不会回显。'}</Text>{formError ? <Alert color="red" title="保存失败">{formError}</Alert> : null}<SimpleGrid cols={{ base: 1, sm: 2 }}><TextInput required label="Provider ID" value={id} disabled={editing} onChange={(e) => setId(e.currentTarget.value)} placeholder="例如 internal-gateway" description={editing ? '唯一标识创建后不可修改' : undefined} /><TextInput required label="展示名称" value={label} onChange={(e) => setLabel(e.currentTarget.value)} placeholder="例如企业模型网关" /><TextInput required label="基础地址" value={baseUrl} onChange={(e) => setBaseUrl(e.currentTarget.value)} placeholder="https://llm.example.com/v1" /><TagsInput required label="可用模型" value={models} onChange={setModels} placeholder="输入模型名后按回车，例如 qwen-max" description="可添加多个模型；能力路由中再选择实际使用的模型" splitChars={[',']} acceptValueOnBlur clearable /><MultiSelect required label="能力" data={[{ value: 'chat', label: '文本对话' }, { value: 'tools', label: '工具调用' }, { value: 'vision', label: '图像理解' }, { value: 'embedding', label: '向量检索' }, { value: 'asr', label: '语音识别' }, { value: 'tts', label: '语音合成' }]} value={capabilities} onChange={setCapabilities} /><PasswordInput label={editing ? '更新 API Key（可选）' : 'API Key（只写）'} value={apiKey} onChange={(e) => setApiKey(e.currentTarget.value)} placeholder={editing ? '留空表示保留已有密钥' : '输入后将加密保存'} /><NumberInput label="采样温度（可选）" value={temperature} onChange={setTemperature} min={0} max={2} step={0.1} decimalScale={2} placeholder="留空使用上游默认值" /><Select label="思考模式" data={[{ value: 'default', label: '使用上游默认值' }, { value: 'true', label: '开启' }, { value: 'false', label: '关闭' }]} value={enableThinking} onChange={(value) => setEnableThinking(value ?? 'default')} /><Checkbox label="绕过 JVM HTTP 代理直连此网关" checked={bypassProxy} onChange={(event) => setBypassProxy(event.currentTarget.checked)} /></SimpleGrid><Group justify="flex-end" mt="xs"><Button variant="default" onClick={onCancel} disabled={saving}>取消</Button><Button loading={saving} disabled={!id.trim() || !label.trim() || !baseUrl.trim() || models.length === 0 || capabilities.length === 0} onClick={() => void submit()}>{editing ? '保存修改' : '加密保存'}</Button></Group></Stack>
}

function RouteCard({ capability, label, route, settings, saving, onSave }: { capability: string; label: string; route: ModelRoute | undefined; settings: ModelSettings; saving: boolean; onSave: (capability: string, route: ModelRoute) => Promise<void> }) {
  const candidates = settings.providers.filter((p) => p.capabilities.includes(capability))
  const current = route?.providerId ?? candidates[0]?.id ?? ''
  const [providerId, setProviderId] = useState(current)
  useEffect(() => setProviderId(current), [current])
  const provider = candidates.find((p) => p.id === providerId)
  const meta = capabilityMeta[capability]
  const routeModel = route?.providerId === providerId && provider?.models.includes(route.model) ? route.model : ''
  const [model, setModel] = useState(routeModel || provider?.models[0] || '')
  const [voice, setVoice] = useState(route?.voice ?? '')
  useEffect(() => setModel(routeModel || provider?.models[0] || ''), [providerId, routeModel, provider?.models])
  useEffect(() => setVoice(route?.voice ?? ''), [route?.voice])
  return <Paper withBorder p="lg" className="model-route-card"><Stack gap="md" h="100%"><Group gap="sm" wrap="nowrap"><span className={`model-route-icon model-route-icon--${capability}`}><CapabilityIcon capability={capability} /></span><div><Text fw={700}>{label}</Text><Text size="xs" c="dimmed">{meta.badge}</Text></div></Group><Text size="sm" c="dimmed" className="model-route-copy">{meta.description}</Text><div><Text size="xs" fw={650} mb={6}>服务提供商</Text><Select className="model-route-select" aria-label={`${label}服务提供商`} data={candidates.map((p) => ({ value: p.id, label: p.label }))} value={providerId} onChange={(value) => setProviderId(value ?? '')} disabled={!candidates.length} maxDropdownHeight={300} comboboxProps={{ width: 340, position: 'bottom-start' }} renderOption={({ option, checked }) => { const item = candidates.find((candidate) => candidate.id === option.value); return <Group justify="space-between" wrap="nowrap" w="100%" py={4}><div className="model-option-copy"><Text size="sm" fw={650}>{item?.label ?? option.label}</Text><Text size="xs" c="dimmed" truncate>{item?.models.join('、')}</Text></div>{checked ? <Text c="teal" fw={800}>✓</Text> : null}</Group> }} /></div><div><Text size="xs" fw={650} mb={6}>模型</Text><Select className="model-route-select" aria-label={`${label}模型`} data={provider?.models ?? []} value={model} onChange={(value) => setModel(value ?? '')} disabled={!provider?.models.length} maxDropdownHeight={300} /></div>{capability === 'tts' ? <TextInput label="音色" aria-label={`${label}音色`} value={voice} onChange={(event) => setVoice(event.currentTarget.value)} placeholder="例如 Cherry" /> : null}<Group justify="space-between" mt="auto" className="model-route-footer"><Badge variant="light">{meta.badge}</Badge><Button size="xs" loading={saving} disabled={!provider || !model || (capability === 'tts' && !voice.trim())} onClick={() => void onSave(capability, { capability, providerId, model, voice: capability === 'tts' ? voice.trim() : null })}>保存配置</Button></Group></Stack></Paper>
}

function CapabilityIcon({ capability }: { capability: string }) {
  const common = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.9, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, role: 'img' }
  if (capability === 'chat') return <svg {...common} aria-label="文本对话"><path d="M21 12a8 8 0 0 1-8 8H7l-4 2 1.3-4.2A8.5 8.5 0 1 1 21 12Z" /><path d="M8 12h.01M12 12h.01M16 12h.01" /></svg>
  if (capability === 'vision') return <svg {...common} aria-label="图像理解"><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="9" cy="9" r="2" /><path d="m21 15-5-5L5 20" /></svg>
  if (capability === 'embedding') return <svg {...common} aria-label="向量检索"><circle cx="6" cy="6" r="2" /><circle cx="18" cy="7" r="2" /><circle cx="8" cy="18" r="2" /><circle cx="18" cy="17" r="2" /><path d="m8 7 8 0M7 8l1 8M10 18h6M17 9v6" /></svg>
  if (capability === 'asr') return <svg {...common} aria-label="语音识别"><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6" /></svg>
  return <svg {...common} aria-label="语音合成"><path d="M11 5 6 9H3v6h3l5 4V5Z" /><path d="M15.5 8.5a5 5 0 0 1 0 7M18 6a8 8 0 0 1 0 12" /></svg>
}
