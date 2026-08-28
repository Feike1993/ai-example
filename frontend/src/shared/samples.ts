import type { PhaseId } from './brand'

export type SampleId =
  | 'chat'
  | 'structured'
  | 'tools'
  | 'agent'
  | 'mcp'
  | 'rag'
  | 'context'
  | 'multiagent'

export type SampleMeta = {
  id: SampleId
  index: number
  label: string
  description: string
  phase: PhaseId
  /** 宣传页主标题（不与侧栏 label/description 重复） */
  tagline: string
  /** 宣传页一段讲解：学什么、为什么 */
  body: string
  concepts: [string, string, string]
  endpoint: string
  docPath: string
}

/** 8 样例元数据：Playground 侧栏与宣传页单一数据源。 */
export const samples: readonly SampleMeta[] = [
  {
    id: 'chat',
    index: 1,
    label: 'Chat',
    description: 'Token / SSE / TTFT',
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
    phase: 3,
    tagline: '会话预算是工程问题',
    body: '模型本身没有跨请求记忆。超预算时用 trim 裁旧轮，或 summarize 压摘要；留意 Lost in the Middle。',
    concepts: ['窗口裁剪 trim', '增量摘要 summarize', 'Lost in the Middle'],
    endpoint: 'POST /ai-example/context/chat',
    docPath: 'docs/samples/07-context.md',
  },
  {
    id: 'multiagent',
    index: 8,
    label: '多 Agent',
    description: 'Orchestrator',
    phase: 3,
    tagline: '拆任务、专员协作、汇总',
    body: 'Orchestrator 拆任务并选择专员；Worker 各自持工具或执笔，显式交接再汇总——信息边界比「多开几个模型」更重要。',
    concepts: ['Orchestrator 编排', 'Worker 信息边界', '显式交接'],
    endpoint: 'POST /ai-example/multiagent/run',
    docPath: 'docs/samples/08-multi-agent.md',
  },
] as const

export function getSampleById(id: SampleId): SampleMeta {
  const found = samples.find((s) => s.id === id)
  if (!found) {
    throw new Error(`Unknown sample id: ${id}`)
  }
  return found
}
