import type { SampleGuideData } from './types'

/** Agent 逐步 tool SSE 讲解。 */
export const agentToolSseGuide: SampleGuideData = {
  title: 'Agent 逐步 SSE',
  concepts: [
    'tool_call / tool_result 在循环中实时推送，不必等全部工具结束。',
    '流式路径支持多跳工具，与同步 POST /agent/react 一致。',
    '仍推送聚合 event:steps 兼容旧客户端。',
    '终答走默认 message；结束有 done。',
  ],
  logic: {
    title: '可观测轨迹',
    problem: 'A4 只在工具轮结束后一次性 steps，难边跑边看。',
    purpose: '把 ReAct 每一步工具调用暴露为 SSE，便于对照与排障。',
    pros: ['实时 Timeline', '多跳完整'],
    cons: ['事件协议需前后端约定'],
    scenarios: ['演示 Agent 如何选工具', '对照框架托管黑盒'],
    steps: [
      { title: '订阅 stream', detail: 'EventSource 听 tool_call / tool_result。' },
      { title: '边收边画', detail: 'Timeline 增量更新。' },
      { title: '终答 + done', detail: 'message 与 done.reachedMaxSteps。' },
    ],
  },
  backend: [
    {
      label: 'Progress 回调',
      language: 'java',
      code: `progress.onToolCall(...);
progress.onToolResult(...);`,
    },
  ],
  frontend: [
    {
      label: '监听事件',
      language: 'tsx',
      code: `source.addEventListener('tool_call', ...)
source.addEventListener('tool_result', ...)`,
    },
  ],
}
