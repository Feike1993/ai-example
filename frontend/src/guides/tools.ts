import type { SampleGuideData } from './types'

/** Tool Calling 样例讲解：@Tool + 按请求挂载 + 底层逻辑。 */
export const toolsGuide: SampleGuideData = {
  title: 'Tool Calling',
  concepts: [
    'Function Calling：模型输出「要调哪个函数 + 参数」，由你的代码执行。',
    '工具用 JSON Schema 描述；粒度优先原子操作，组合逻辑放 Agent Loop。',
    '工具失败要返回错误字符串让模型改主意，而不是直接把进程打崩。',
    '不要把全部工具挂到全局 Client：结构化输出场景会被 tool 消息污染。',
  ],
  logic: {
    title: 'Tool Calling 底层逻辑',
    problem: '纯文本回复无法查天气、算数或写库；若让模型「假装执行」，结果不可信且无法审计。',
    purpose: '让模型只产出调用意图（函数名+参数），由应用真实执行工具并把结果回填，再生成自然语言答案。',
    pros: [
      '权限与副作用留在应用侧，可审计、可限流。',
      'Schema 约束参数形状，比自由文本好解析。',
      '失败可返回错误字符串，模型可改参重试。',
    ],
    cons: [
      '单次调用仍是「一步」；多步编排需 Agent Loop。',
      '工具描述不佳会导致乱调或漏调。',
      '挂载过多工具会占窗口、干扰无关场景。',
    ],
    scenarios: [
      '查询外部 API、计算器、订单状态等原子动作。',
      '需要模型决策「调不调、调哪个」的交互式助手。',
    ],
    steps: [
      {
        title: '模型不会真的「调用 API」',
        detail:
          '它只生成结构化意图：函数名 + 参数；真正执行发生在你的进程里，由应用代码完成。',
      },
      {
        title: '工具用 Schema 挂进上下文',
        detail:
          '@Tool / JSON Schema（名、描述、参数）进入提示；模型据此决定是否调用、调哪个、传什么参数。',
      },
      {
        title: '一轮典型协议',
        detail:
          '用户话 → 模型返回 tool_calls → 应用执行 → 把结果作为 tool 消息回填 → 模型再生成自然语言答案（本样例：天气 + 计算器）。',
      },
      {
        title: '粒度要原子',
        detail:
          '单个工具只做一件事；多步编排留给 Agent Loop，避免一个巨型万能工具难调试、难复用。',
      },
      {
        title: '失败返回错误字符串',
        detail:
          '让模型改参数或换策略，而不是把服务进程打崩；错误文本本身成为下一轮上下文。',
      },
      {
        title: '按请求挂载，不全局一把梭',
        detail:
          '结构化抽取等场景不要挂工具，避免消息形态被 tool 调用污染；工具按场景按请求挂载。',
      },
    ],
  },
  backend: [
    {
      label: '工具定义 — DemoTools',
      language: 'java',
      code: `@Tool(description = "查询指定城市的天气。演示用，返回模拟数据。")
public String getWeather(@ToolParam(description = "城市名，例如 北京") String city) {
    if (city == null || city.isBlank()) {
        return "请提供城市名";
    }
    return switch (city.trim()) {
        case "北京" -> "北京：晴，25°C，北风 2 级";
        case "上海" -> "上海：多云，28°C，东南风 3 级";
        default -> city.trim() + "：阴，22°C";
    };
}

@Tool(description = "计算两个数字的和")
public double add(
    @ToolParam(description = "第一个加数") double a,
    @ToolParam(description = "第二个加数") double b
) {
    return a + b;
}`,
    },
    {
      label: '按请求挂载 — ToolSampleService',
      language: 'java',
      code: `public String chatWithTools(String prompt, String provider) {
    return registry.plainClient(provider)
        .prompt()
        .system("你是助手。需要天气或加法时必须调用工具，不要编造。")
        .user(prompt)
        .tools(demoTools)  // 仅本请求挂载，不绑全局 Client
        .call()
        .content();
}`,
    },
  ],
  frontend: [
    {
      label: '调用 Tools API',
      language: 'tsx',
      code: `const run = async () => {
  setError(null)
  setResult(null)
  setLoading(true)
  try {
    const data = await postJson<ToolChatResponse>(
      \`\${API_BASE}/tools\`,
      { prompt, provider },
    )
    setResult(data)
  } catch (err) {
    setError(describeError(err))
  } finally {
    setLoading(false)
  }
}`,
    },
  ],
}
