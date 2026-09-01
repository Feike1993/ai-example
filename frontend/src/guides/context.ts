import type { SampleGuideData } from './types'

/** 上下文工程样例讲解。 */
export const contextGuide: SampleGuideData = {
  title: '上下文工程',
  concepts: [
    '模型无跨请求记忆；「记得」= 你把历史再塞进窗口。',
    'trim：丢最旧轮次；summarize：旧轮次压成摘要再拼最近轮次。',
    '默认 store=jdbc（PostgreSQL）持久化；store=memory 为进程内 Map。',
    'Token 预算用字符/4 启发式即可建立直觉；精确 tokenizer 见 backlog。',
  ],
  logic: {
    title: '上下文组装逻辑',
    problem: '多轮对话历史无限增长会撑爆窗口、抬高费用，且旧消息不一定都该进模型。',
    purpose: '在应用侧存会话，并按条数/token 预算裁剪后再调用模型，同时暴露可观测裁剪字段。',
    pros: [
      '会话可控、可切换 memory/jdbc 存储。',
      '预算策略透明（droppedCount 等）。',
      '与「模型自带记忆」解耦，便于审计。',
    ],
    cons: [
      '裁剪可能丢掉关键旧事实。',
      '近似 token 与真实计费仍有偏差。',
      '摘要策略需额外设计，本样例偏裁剪。',
    ],
    scenarios: [
      '多轮客服、辅导类会话。',
      '需要限制单次请求上下文成本的产品。',
    ],
    steps: [
      {
        title: '会话存在应用侧',
        detail: 'ChatSessionStore：jdbc 写 chat_session_message；memory 为 ConcurrentHashMap。',
      },
      {
        title: '先预算，再调用',
        detail: '按 maxMessages / tokenBudget 选出送入模型的子集，再追加本轮 user。',
      },
      {
        title: '响应带回可观测字段',
        detail: 'rawMessageCount / sentMessageCount / approxTokens / droppedCount / summary。',
      },
    ],
  },
  backend: [
    {
      label: 'Trim — ContextBudget',
      language: 'java',
      code: `TrimResult trimmed = ContextBudget.trim(history, maxMessages, tokenBudget);
window = trimmed.messages();
window.add(new UserMessage(prompt));`,
    },
  ],
  frontend: [
    {
      label: '多轮同一 sessionId',
      language: 'tsx',
      code: `await postJson(\`\${API_BASE}/context/chat\`, {
  sessionId, prompt, provider, strategy,
})`,
    },
  ],
}
