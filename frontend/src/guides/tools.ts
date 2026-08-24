import type { SampleGuideData } from './types'

/** Tool Calling 样例讲解：@Tool + 按请求挂载。 */
export const toolsGuide: SampleGuideData = {
  title: 'Tool Calling',
  concepts: [
    'Function Calling：模型输出「要调哪个函数 + 参数」，由你的代码执行。',
    '工具用 JSON Schema 描述；粒度优先原子操作，组合逻辑放 Agent Loop。',
    '工具失败要返回错误字符串让模型改主意，而不是直接把进程打崩。',
    '不要把全部工具挂到全局 Client：结构化输出场景会被 tool 消息污染。',
  ],
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
