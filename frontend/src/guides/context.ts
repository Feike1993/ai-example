import type { SampleGuideData } from './types'

/** 上下文工程样例讲解。 */
export const contextGuide: SampleGuideData = {
  title: '上下文工程',
  concepts: [
    '模型无跨请求记忆；「记得」= 你把历史再塞进窗口。',
    'trim：丢最旧轮次；summarize：旧轮次压成摘要再拼最近轮次。',
    'Token 预算用字符/4 启发式即可建立直觉；精确 tokenizer 见 backlog。',
    'Lost in the Middle：中间内容更容易被忽略；裁剪会改变「中间」位置。',
  ],
  logic: {
    title: '上下文组装逻辑',
    steps: [
      {
        title: '会话存在应用侧',
        detail: '本仓用进程内 Map；重启即清空。生产可换 Redis/DB。',
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
