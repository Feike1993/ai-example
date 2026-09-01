import type { AdvancedPhaseId, LearningStage, PhaseId } from './brand'

/** 基础 8 样例 id（宣传页与 Playground 共用）。 */
export type BaselineSampleId =
  | 'chat'
  | 'structured'
  | 'tools'
  | 'agent'
  | 'mcp'
  | 'rag'
  | 'context'
  | 'multiagent'

/** 进阶样例 id（仅 Playground）。 */
export type AdvancedSampleId =
  | 'hybridRag'
  | 'eval'
  | 'memory'
  | 'hyde'
  | 'semanticChunk'
  | 'parentChild'
  | 'memoryExtract'

/** Playground 全部 Tab id。 */
export type PlaygroundSampleId = BaselineSampleId | AdvancedSampleId

/** @deprecated 宣传页仍用 BaselineSampleId；保留别名减少改动面。 */
export type SampleId = BaselineSampleId

export type SampleMeta = {
  id: PlaygroundSampleId
  index: number
  label: string
  description: string
  stage: LearningStage
  phase: PhaseId | AdvancedPhaseId
  /** 宣传页主标题（不与侧栏 label/description 重复） */
  tagline: string
  /** 宣传页一段讲解：学什么、为什么 */
  body: string
  concepts: [string, string, string]
  endpoint: string
  docPath: string
}

export type BaselineSampleMeta = SampleMeta & { id: BaselineSampleId; stage: 'baseline'; phase: PhaseId }
export type AdvancedSampleMeta = SampleMeta & {
  id: AdvancedSampleId
  stage: 'advanced'
  phase: AdvancedPhaseId
}

/** 样例元数据：Playground 侧栏与宣传页（基础 8 项）单一数据源。 */
export const samples: readonly SampleMeta[] = [
  {
    id: 'chat',
    index: 1,
    label: 'Chat',
    description: 'Token / SSE / TTFT',
    stage: 'baseline',
    phase: 1,
    tagline: '先学会和 LLM 对话',
    body: '从同步调用走到 SSE 流式：观察采样温度、首 token 时间（TTFT），建立对 Token 与延迟的直觉。',
    concepts: ['采样温度', 'SSE 流式', '首 token 时间 TTFT'],
    endpoint: 'POST /ai-example/chat',
    docPath: 'docs/samples/01-chat.md',
  },
  {
    id: 'structured',
    index: 2,
    label: '结构化输出',
    description: '工单 JSON',
    stage: 'baseline',
    phase: 1,
    tagline: '让模型吐出可解析 JSON',
    body: '用 Schema 约束输出形态；遇到围栏或残缺 JSON 时剥离、重试与修复，而不是手工抠字符串。',
    concepts: ['JSON Schema', '围栏剥离', '失败重试修复'],
    endpoint: 'POST /ai-example/structured/ticket',
    docPath: 'docs/samples/02-structured.md',
  },
  {
    id: 'tools',
    index: 3,
    label: 'Tool Calling',
    description: '天气 + 计算器',
    stage: 'baseline',
    phase: 1,
    tagline: '模型决策，应用执行',
    body: '模型只输出调用意图；真正跑天气、计算器的是你的 `@Tool`。演示工具保持幂等、可离线。',
    concepts: ['Function Calling', '@Tool 注解', '幂等演示工具'],
    endpoint: 'POST /ai-example/tools',
    docPath: 'docs/samples/03-tools.md',
  },
  {
    id: 'agent',
    index: 4,
    label: 'Agent Loop',
    description: 'ReAct / Framework',
    stage: 'baseline',
    phase: 1,
    tagline: '四步循环直到答完或熔断',
    body: 'Perceive → Reason → Act → Observe 显式成环；用 maxSteps 熔断，避免工具调用无限转圈。第一期 Memory 只用当轮消息。',
    concepts: ['Perceive → Reason → Act → Observe', 'maxSteps 熔断', '当轮消息 Memory'],
    endpoint: 'POST /ai-example/agent/react',
    docPath: 'docs/samples/04-agent.md',
  },
  {
    id: 'mcp',
    index: 5,
    label: 'MCP',
    description: '工具协议标准化',
    stage: 'baseline',
    phase: 2,
    tagline: '工具接入像 USB-C 一样统一',
    body: 'Function Calling 是模型能力；MCP 是工具协议。Host / Client / Server 分层后，换工具不必重写 Agent Loop。',
    concepts: ['Host / Client / Server', 'Streamable HTTP', '协议 vs Function Calling'],
    endpoint: 'POST /ai-example/mcp/chat',
    docPath: 'docs/samples/05-mcp.md',
  },
  {
    id: 'rag',
    index: 6,
    label: 'RAG',
    description: 'pgvector 检索增强',
    stage: 'baseline',
    phase: 2,
    tagline: '先检索再生成，减少胡说',
    body: '离线分块与 Embedding 入 pgvector；在线检索拼上下文再生成。空检索直接拒答，不编造语料外内容。',
    concepts: ['分块 → Embedding → 检索', 'pgvector 向量库', '空检索拒答'],
    endpoint: 'POST /ai-example/rag/query',
    docPath: 'docs/samples/06-rag.md',
  },
  {
    id: 'context',
    index: 7,
    label: '上下文',
    description: 'trim / summarize',
    stage: 'baseline',
    phase: 3,
    tagline: '会话预算是工程问题',
    body: '模型本身没有跨请求记忆。超预算时用 trim 裁旧轮，或 summarize 压摘要。第五期起默认 PostgreSQL 持久化（store=jdbc），重启可续聊。',
    concepts: ['窗口裁剪 trim', '增量摘要 summarize', 'PG 持久会话'],
    endpoint: 'POST /ai-example/context/chat',
    docPath: 'docs/samples/07-context.md',
  },
  {
    id: 'multiagent',
    index: 8,
    label: '多 Agent',
    description: 'Orchestrator',
    stage: 'baseline',
    phase: 3,
    tagline: '拆任务、专员协作、汇总',
    body: 'Orchestrator 拆任务并选择专员；Worker 各自持工具或执笔，显式交接再汇总——信息边界比「多开几个模型」更重要。',
    concepts: ['Orchestrator 编排', 'Worker 信息边界', '显式交接'],
    endpoint: 'POST /ai-example/multiagent/run',
    docPath: 'docs/samples/08-multi-agent.md',
  },
  {
    id: 'hybridRag',
    index: 9,
    label: 'Hybrid RAG',
    description: '向量 + 全文 + RRF',
    stage: 'advanced',
    phase: 4,
    tagline: '向量与关键词双路召回',
    body: '在纯向量之上增加 PostgreSQL 全文检索路，用 RRF 融合排名；compare 接口并排对照 vector / hybrid。',
    concepts: ['PG 全文检索', 'RRF 融合', 'vector vs hybrid 对照'],
    endpoint: 'POST /ai-example/rag/query/compare',
    docPath: 'docs/samples/09-hybrid-rag.md',
  },
  {
    id: 'eval',
    index: 10,
    label: '评测',
    description: 'golden suite',
    stage: 'advanced',
    phase: 4,
    tagline: 'golden 用例回归',
    body: '跑 classpath eval/golden/*.json，统计通过率、步数、工具失败率与 Token 用量。',
    concepts: ['golden 用例', '通过率报告', '多 target 覆盖'],
    endpoint: 'POST /ai-example/eval/run',
    docPath: 'docs/samples/10-eval.md',
  },
  {
    id: 'memory',
    index: 11,
    label: '长期记忆',
    description: 'pgvector 事实库',
    stage: 'advanced',
    phase: 5,
    tagline: '跨会话写入与召回事实',
    body: '与 RAG 演示语料隔离的 corpus；remember → recall → chat。空召回拒答，不编造。',
    concepts: ['独立 corpus', 'remember / recall', '与会话窗口区分'],
    endpoint: 'POST /ai-example/memory/chat',
    docPath: 'docs/samples/12-long-term-memory.md',
  },
  {
    id: 'hyde',
    index: 12,
    label: 'HyDE',
    description: '假想文档检索',
    stage: 'advanced',
    phase: 6,
    tagline: '用假想段落拉近问题与文档',
    body: 'Chat 生成假想知识库段落再 Embedding 检索；对照 none / rewrite；假想正文不得进 sources。',
    concepts: ['Hypothetical Document', 'queryExpansion', 'grounded sources'],
    endpoint: 'POST /ai-example/rag/query/compare-expansion',
    docPath: 'docs/samples/14-hyde.md',
  },
  {
    id: 'semanticChunk',
    index: 13,
    label: '语义分块',
    description: 'token vs 结构切',
    stage: 'advanced',
    phase: 7,
    tagline: '切块边界改变召回单元',
    body: '对照 TokenTextSplitter 与 Markdown 标题/段落语义切；两套 corpus 并存。',
    concepts: ['结构感知分块', 'corpus 隔离', 'compare-chunking'],
    endpoint: 'POST /ai-example/rag/query/compare-chunking',
    docPath: 'docs/samples/15-semantic-chunk.md',
  },
  {
    id: 'parentChild',
    index: 14,
    label: '父子文档',
    description: '子检索父上下文',
    stage: 'advanced',
    phase: 7,
    tagline: '小块检索，大块生成',
    body: '子块 Embedding 检索；命中后展开 parentText 去重拼上下文。',
    concepts: ['parent-child', 'expand-parent', '粒度解耦'],
    endpoint: 'POST /ai-example/rag/query (chunkingStrategy=parent_child)',
    docPath: 'docs/samples/16-parent-child.md',
  },
  {
    id: 'memoryExtract',
    index: 15,
    label: '自动抽记忆',
    description: '对话抽事实写入',
    stage: 'advanced',
    phase: 8,
    tagline: '从对话抽出短事实再 remember',
    body: '显式 POST /memory/extract；Chat 抽 JSON 事实列表后走现有 remember（去重/合并）。不静默塞进 context/chat。',
    concepts: ['extract', 'facts → remember', 'session 快照可选'],
    endpoint: 'POST /ai-example/memory/extract',
    docPath: 'docs/samples/17-memory-extract.md',
  },
] as const

/** 基础闭环 8 样例（宣传页数据源）。 */
export const baselineSamples: readonly BaselineSampleMeta[] = samples.filter(
  (s): s is BaselineSampleMeta => s.stage === 'baseline',
)

/** 进阶样例（仅 Playground）。 */
export const advancedSamples: readonly AdvancedSampleMeta[] = samples.filter(
  (s): s is AdvancedSampleMeta => s.stage === 'advanced',
)

export function getSampleById(id: BaselineSampleId): BaselineSampleMeta {
  const found = baselineSamples.find((s) => s.id === id)
  if (!found) {
    throw new Error(`Unknown sample id: ${id}`)
  }
  return found
}

export function getPlaygroundSampleById(id: PlaygroundSampleId): SampleMeta {
  const found = samples.find((s) => s.id === id)
  if (!found) {
    throw new Error(`Unknown playground sample id: ${id}`)
  }
  return found
}
