import { describe, expect, it } from 'vitest'
import { mergeAgentSteps, mergeAllAgentSteps, type StepPatch } from './agentSteps'
import type { AgentStep } from './sseClient'

const EMPTY: AgentStep[] = []

describe('mergeAgentSteps', () => {
  it('首个片段补齐缺失字段为空串', () => {
    const merged = mergeAgentSteps(EMPTY, { index: 0, toolName: 'search', toolArgs: '{"q":"x"}' })

    expect(merged).toEqual([
      { index: 0, toolName: 'search', assistantText: '', toolArgs: '{"q":"x"}', toolResult: '', denied: false },
    ])
  })

  it('后到的 toolResult 与先到的 toolArgs 合并进同一步', () => {
    const merged = mergeAllAgentSteps(EMPTY, [
      { index: 0, toolName: 'search', assistantText: '我来查', toolArgs: '{"q":"x"}' },
      { index: 0, toolName: 'search', toolResult: '找到 3 条' },
    ])

    expect(merged).toEqual([
      { index: 0, toolName: 'search', assistantText: '我来查', toolArgs: '{"q":"x"}', toolResult: '找到 3 条', denied: false },
    ])
  })

  it('先到 result 后到 args 也不丢字段', () => {
    const merged = mergeAllAgentSteps(EMPTY, [
      { index: 1, toolName: 'calc', toolResult: '42' },
      { index: 1, toolName: 'calc', toolArgs: '{"a":40,"b":2}' },
    ])

    expect(merged[0]).toMatchObject({ toolArgs: '{"a":40,"b":2}', toolResult: '42' })
  })

  it('乱序到达的步骤按 index 升序排列', () => {
    const merged = mergeAllAgentSteps(EMPTY, [
      { index: 2, toolName: 'c' },
      { index: 0, toolName: 'a' },
      { index: 1, toolName: 'b' },
    ])

    expect(merged.map((step) => step.index)).toEqual([0, 1, 2])
  })

  it('同一 index 下不同工具算两步，不会互相覆盖', () => {
    const merged = mergeAllAgentSteps(EMPTY, [
      { index: 0, toolName: 'search', toolResult: '搜索结果' },
      { index: 0, toolName: 'calc', toolResult: '计算结果' },
    ])

    expect(merged).toHaveLength(2)
    expect(merged.map((step) => step.toolName).sort()).toEqual(['calc', 'search'])
  })

  it('重复片段幂等，不会产生第二条', () => {
    const patch: StepPatch = { index: 0, toolName: 'search', toolResult: '找到 3 条' }
    const once = mergeAgentSteps(EMPTY, patch)
    const twice = mergeAgentSteps(once, patch)

    expect(twice).toEqual(once)
  })

  it('空串是有效值，会覆盖旧值；undefined 才保留旧值', () => {
    const base = mergeAgentSteps(EMPTY, { index: 0, toolName: 't', toolResult: '旧' })

    expect(mergeAgentSteps(base, { index: 0, toolName: 't', toolResult: '' })[0].toolResult).toBe('')
    expect(mergeAgentSteps(base, { index: 0, toolName: 't' })[0].toolResult).toBe('旧')
  })

  it('denied 标记会保留，不被后到的普通片段抹掉', () => {
    const merged = mergeAllAgentSteps(EMPTY, [
      { index: 0, toolName: 'rebuild_index', denied: true, toolResult: 'denied' },
      { index: 0, toolName: 'rebuild_index', toolArgs: '{}' },
    ])
    expect(merged[0].denied).toBe(true)
    expect(merged[0].toolResult).toBe('denied')
  })

  it('不修改传入的数组', () => {
    const base = mergeAgentSteps(EMPTY, { index: 0, toolName: 'a' })
    const snapshot = structuredClone(base)
    mergeAgentSteps(base, { index: 1, toolName: 'b' })

    expect(base).toEqual(snapshot)
  })
})

