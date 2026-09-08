import type { AgentStep } from './sseClient'

/**
 * Agent 步骤合并：把陆续到达的 step 片段拼成完整时间线。
 *
 * 从面板里抽成纯函数的原因很实际——它是整条链路上最容易出错的逻辑：
 * 工具调用和工具结果是两个独立事件，可能乱序、可能重复、可能只到一半，
 * 而错误表现（少一步、字段被清空）在 UI 上很难一眼看出来。做成纯函数后可以直接表驱动测。
 *
 * 合并规则：以 `index + toolName` 为键，新片段里为 undefined 的字段保留旧值，
 * 绝不用空串覆盖已有内容——先到的 toolResult 不该被后到的空 toolArgs 抹掉。
 */

/** 合并用的键：同一步里工具名不会变，但同一 index 可能对应不同工具。 */
function keyOf(step: { index: number; toolName: string }): string {
  return `${step.index}:${step.toolName}`
}

export type StepPatch = Partial<AgentStep> & { index: number; toolName: string }

/**
 * 把一个片段并入现有步骤列表。
 *
 * @param steps 现有步骤（按 index 升序）
 * @param patch 新到达的片段
 * @returns 新的步骤列表，按 index 升序；输入不被修改
 */
export function mergeAgentSteps(steps: readonly AgentStep[], patch: StepPatch): AgentStep[] {
  const byKey = new Map<string, AgentStep>()
  for (const step of steps) {
    byKey.set(keyOf(step), step)
  }

  const key = keyOf(patch)
  const prev = byKey.get(key)
  byKey.set(key, {
    index: patch.index,
    toolName: patch.toolName,
    assistantText: patch.assistantText ?? prev?.assistantText ?? '',
    toolArgs: patch.toolArgs ?? prev?.toolArgs ?? '',
    toolResult: patch.toolResult ?? prev?.toolResult ?? '',
  })

  return Array.from(byKey.values()).sort((a, b) => a.index - b.index)
}

/**
 * 批量合并，顺序等价于逐条调用 {@link mergeAgentSteps}。
 *
 * @param steps   现有步骤
 * @param patches 若干片段
 * @returns 合并后的步骤列表
 */
export function mergeAllAgentSteps(steps: readonly AgentStep[], patches: readonly StepPatch[]): AgentStep[] {
  return patches.reduce<AgentStep[]>((acc, patch) => mergeAgentSteps(acc, patch), [...steps])
}
