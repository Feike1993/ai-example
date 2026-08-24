import type { SampleGuideData } from './types'

/** Chat 样例讲解：Token / 采样 / SSE。 */
export const chatGuide: SampleGuideData = {
  title: 'Chat',
  concepts: [
    'Token 是计费和上下文窗口的单位，不是字符。',
    '上下文窗口 = system + user + 历史 + 工具定义 + 输出。',
    'temperature 低更确定，高更发散；面试评分 / 结构化抽取用低温度。',
    '流式降低 TTFT（首 token 时间），总耗时不一定变短。',
  ],
  backend: [
    {
      label: '同步补全 — ChatSampleService.chat',
      language: 'java',
      code: `public String chat(String prompt, Double temperature, String provider) {
    ChatClient chatClient = registry.plainClient(provider);
    // 1. 构建 Prompt
    var spec = chatClient.prompt().user(prompt);
    // 2. 可选：设置温度参数
    if (temperature != null) {
        spec = spec.options(OpenAiChatOptions.builder().temperature(temperature));
    }
    return spec.call().content();
}`,
    },
    {
      label: 'SSE 流式 — ChatSampleService.stream',
      language: 'java',
      code: `public Flux<String> stream(String prompt, String provider) {
    return registry.plainClient(provider)
        .prompt()
        .user(prompt)
        .stream()
        .content();
}`,
    },
  ],
  frontend: [
    {
      label: '同步 POST',
      language: 'tsx',
      code: `const data = await postJson<ChatResponse>(\`\${API_BASE}/chat\`, {
  prompt,
  temperature,
  provider,
})
setTtftMs(Math.round(performance.now() - started))
setResult(data)`,
    },
    {
      label: 'SSE 订阅 — streamChat',
      language: 'tsx',
      code: `export function streamChat(
  prompt: string,
  provider: string,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (error: Error) => void,
): () => void {
  const params = new URLSearchParams({ prompt })
  if (provider) params.set('provider', provider)
  const source = new EventSource(\`\${API_BASE}/chat/stream?\${params}\`)

  source.onmessage = (event) => {
    if (event.data === '' || event.data === '[DONE]') return
    onChunk(decodeSseData(event.data))
  }
  // onerror：已收到数据则视为正常结束，否则报错
  return () => { source.close(); onDone() }
}`,
    },
  ],
}
