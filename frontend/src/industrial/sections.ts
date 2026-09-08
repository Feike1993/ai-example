/** 工业级区的导航项 id。 */
export type IndustrialSectionId = 'chat' | 'observability' | 'security'

export type IndustrialSection = {
  id: IndustrialSectionId
  label: string
  description: string
  /** 未就绪的面板在导航里灰掉，而不是从列表里藏起来——路线图对使用者是有用信息。 */
  ready: boolean
}

/**
 * 导航元数据。与样例场的 samples.ts 分开维护：
 * 两边的分类维度不同（课程期数 vs 生产能力面），合并只会互相牵制。
 */
export const industrialSections: IndustrialSection[] = [
  {
    id: 'chat',
    label: '生产问答',
    description: '严格 SSE 契约 + 断线续传',
    ready: true,
  },
  {
    id: 'observability',
    label: '可观测',
    description: '指标 / Trace（第三阶段）',
    ready: false,
  },
  {
    id: 'security',
    label: '安全',
    description: '鉴权 / 限流 / 审计（第三阶段）',
    ready: false,
  },
]
