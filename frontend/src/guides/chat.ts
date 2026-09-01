import type { SampleGuideData } from './types'

/** Chat 样例讲解：Token / 采样 / SSE + 对话底层逻辑。 */
export const chatGuide: SampleGuideData = {
  title: 'Chat',
  concepts: [
    'Token 是计费和上下文窗口的单位，不是字符。',
    '上下文窗口 = system + user + 历史 + 工具定义 + 输出。',
    'temperature 低更确定，高更发散；面试评分 / 结构化抽取用低温度。',
    '流式降低 TTFT（首 token 时间），总耗时不一定变短。',
  ],
  logic: {
    title: '对话底层逻辑',
    problem: '应用需要把自然语言能力接进产品，但模型无状态、按 token 计费，且同步等待整段回复会拖慢体验。',
    purpose: '掌握消息角色、上下文窗口、采样与同步/流式交付，能正确发起对话并控制成本与时延。',
    pros: [
      '接入简单，单轮即可验证模型与 Provider。',
      '流式降低首 token 时延，体验更接近「正在写」。',
      'temperature 等参数可按场景调节确定性。',
    ],
    cons: [
      '无跨请求记忆，历史要自行回传，易撑爆窗口。',
      '按 token 计费，长对话成本与延迟上升。',
      '纯对话无法可靠执行外部动作或返回结构化数据。',
    ],
    scenarios: [
      '客服问答、写作助手、头脑风暴等开放式对话。',
      '作为 Tools / Agent / RAG 等能力的底座调用层。',
    ],
    steps: [
      {
        title: '消息是对话的原子单位',
        detail:
          '一轮对话由带角色的消息组成：system（人设/约束）、user（提问）、assistant（模型回复）。本样例多为单轮 user；多轮则把历史 assistant/user 依次堆叠进请求。',
      },
      {
        title: '每次请求都是「整段上下文」',
        detail:
          '模型本身没有跨请求记忆。「记得上文」= 客户端把历史消息再塞进本次窗口。窗口大致 = system + 历史 + 本轮 user +（预留）输出；超窗会被截断或拒收。',
      },
      {
        title: '补全 = 自回归预测下一个 Token',
        detail:
          '给定当前序列，模型产出下一 token 的概率分布，选出一个 token 拼回序列，再继续预测，直到遇到停止条件（结束符、长度上限等）。',
      },
      {
        title: '采样决定「选哪个 next token」',
        detail:
          'temperature 等参数调节分布的尖锐或平坦：低温度更确定（评分、结构化抽取），高温度更发散（创意写作）。',
      },
      {
        title: '同步与流式只是交付方式',
        detail:
          '生成仍是逐 token。同步等拼完再返回；流式边生成边推给前端，所以 TTFT（首 token 时间）变短，总生成时间通常差不多。',
      },
      {
        title: '计费与截断都盯 Token',
        detail:
          '输入与输出一般按 token 计费；历史越长，窗口占用与费用越高，因此要主动控制对话历史长度。',
      },
    ],
  },
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
