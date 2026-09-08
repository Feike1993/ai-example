import { describe, expect, it } from 'vitest'
import type { SessionMessage } from './productionApi'
import { toTurns } from './sessionTurns'

function message(seq: number, turnId: string, role: SessionMessage['role'], content: string): SessionMessage {
  return { seq, turnId, role, content, runId: null, createdAt: null }
}

describe('toTurns', () => {
  it('按 turnId 把扁平消息还原成一问一答', () => {
    const turns = toTurns([
      message(0, 't1', 'user', '问题一'),
      message(1, 't1', 'assistant', '回答一'),
      message(2, 't2', 'user', '问题二'),
      message(3, 't2', 'assistant', '回答二'),
    ])

    expect(turns).toHaveLength(2)
    expect(turns[0]).toMatchObject({ question: '问题一', answer: '回答一' })
    expect(turns[1]).toMatchObject({ question: '问题二', answer: '回答二' })
  })

  it('夹杂 system 消息时不会错位', () => {
    // 这正是不能按奇偶位置切分的原因：多一条 system，后面全部串行错开
    const turns = toTurns([
      message(0, 't0', 'system', '你是助手'),
      message(1, 't1', 'user', '问题一'),
      message(2, 't1', 'assistant', '回答一'),
    ])

    expect(turns).toHaveLength(1)
    expect(turns[0]).toMatchObject({ question: '问题一', answer: '回答一' })
  })

  it('只有半截的轮次也保留，另一半留空而不是丢掉整轮', () => {
    const turns = toTurns([message(0, 't1', 'user', '问题一')])

    expect(turns).toHaveLength(1)
    expect(turns[0]).toMatchObject({ question: '问题一', answer: '' })
  })

  it('空历史返回空列表', () => {
    expect(toTurns([])).toEqual([])
  })
})
