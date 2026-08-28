/** 宣传页与 Playground 共用的品牌色与字体 token。 */
export const brand = {
  accent: '#2a6f5f',
  accentDark: '#245f51',
  advanced: '#1e4d42',
  bg: '#e4ebe6',
  sidebar: '#d7e2dc',
  heroDark: '#0f1a14',
  text: '#14241c',
  textMuted: 'rgba(20, 36, 28, 0.78)',
  white: '#f3f8f5',
  phase1: '#2a6f5f',
  phase2: '#549e88',
  phase3: '#7bb8a3',
  fontHeading: 'Syne, "Noto Sans SC", sans-serif',
  fontBody: 'Figtree, "Noto Sans SC", sans-serif',
} as const

export type PhaseId = 1 | 2 | 3

export type LearningStage = 'baseline' | 'advanced'

/** 三期标签文案。 */
export const phaseLabels: Record<PhaseId, string> = {
  1: '第一期 · 最小 Agent 闭环',
  2: '第二期 · MCP + RAG',
  3: '第三期 · 上下文 + 多 Agent',
}

/** Playground 侧栏阶段切换短标签。 */
export const stageLabels: Record<LearningStage, string> = {
  baseline: '基础',
  advanced: '进阶',
}

/** Playground 阶段切换副标题（一行提示）。 */
export const stageHints: Record<LearningStage, string> = {
  baseline: 'v0.2.0 · 8 样例',
  advanced: '第四期 · 2 样例',
}
