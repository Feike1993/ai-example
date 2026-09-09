import type { SessionMessage } from './productionApi'
import type { AgentStep, ProductionSource, UsagePayload } from './sseClient'

/**
 * 界面上的一轮问答。
 *
 * `incomplete` 与 `unpersisted` 是两种不同的「不可靠」，必须分开表达：
 * 前者是正文可能缺了一截且后端没落库，后者是回答完整但没写进历史。
 * 合并成一个「有问题」标记，用户就分不清该重发还是该接着问。
 */
export type Turn = {
  question: string
  answer: string
  sources: ProductionSource[]
  usage: UsagePayload | null
  /** 未收到 done 的那一轮：正文可能不完整，且没有写进历史 */
  incomplete?: boolean
  /** 收到了 done 但后端没能落库 */
  unpersisted?: boolean
  /** Agent 工具步骤 */
  steps?: AgentStep[]
}

/**
 * 把扁平的消息序列还原成一问一答。
 *
 * 按 turnId 分组而不是按奇偶位置切：位置假设一旦被历史里的 system 消息或
 * 未来的多消息轮次打破，整个列表就会错位，而且错得很安静。
 *
 * @param messages 后端按 seq 升序返回的消息
 * @returns 轮次列表，顺序与消息中首次出现的 turnId 一致
 */
export function toTurns(messages: SessionMessage[]): Turn[] {
  const byTurn = new Map<string, Turn>()
  const order: string[] = []
  for (const message of messages) {
    if (message.role !== 'user' && message.role !== 'assistant') {
      continue
    }
    let turn = byTurn.get(message.turnId)
    if (!turn) {
      turn = { question: '', answer: '', sources: [], usage: null }
      byTurn.set(message.turnId, turn)
      order.push(message.turnId)
    }
    if (message.role === 'user') {
      turn.question = message.content
    } else {
      turn.answer = message.content
    }
  }
  return order.map((id) => byTurn.get(id) as Turn)
}
