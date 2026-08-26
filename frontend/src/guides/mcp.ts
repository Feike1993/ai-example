import type { SampleGuideData } from './types'

/** MCP 样例讲解：协议层工具 vs 本地 @Tool。 */
export const mcpGuide: SampleGuideData = {
  title: 'MCP',
  concepts: [
    'Function Calling 是 LLM 能力；MCP 是工具接入协议；Agent 是 Loop + Tools 的系统概念。',
    'Host / Client / Server：本仓 Java 同进程暴露 Streamable HTTP `/mcp`，样例聊天复用 Server 注册的 ToolCallback。',
    'stdio 适合本地子进程；Streamable HTTP 适合远程/生产（Spring AI 2.0 已弃用纯 SSE 传输）。',
    'MCP 只标准化连接；Agent Loop / 熔断仍在第一期那一层。',
  ],
  logic: {
    title: 'MCP 底层逻辑',
    steps: [
      {
        title: '先有工具，再有协议外壳',
        detail:
          '仍是 @Tool / JSON Schema；MCP Server 把同一批工具用 JSON-RPC 暴露出去，Client 发现后挂到 ChatClient。',
      },
      {
        title: '本仓同进程学习捷径',
        detail:
          '为避免启动期 Client 连自己的鸡生蛋，聊天样例直接复用注册给 MCP Server 的 ToolCallbackProvider；生产请拆 Server/Client 并加鉴权。',
      },
      {
        title: '对照本地 Tool Calling',
        detail:
          'POST /tools 是进程内 .tools(bean)；POST /mcp/chat 响应里的 toolNames 来自 MCP 注册表，便于并排对比。',
      },
    ],
  },
  backend: [
    {
      label: '注册 MCP Tools — McpToolConfiguration',
      language: 'java',
      code: `@Bean
public ToolCallbackProvider mcpServerTools(DemoTools demoTools, CpkTools cpkTools) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(demoTools, cpkTools)
        .build();
}`,
    },
    {
      label: '样例聊天 — McpSampleService',
      language: 'java',
      code: `List<String> toolNames = listToolNames();
String content = registry.plainClient(provider)
    .prompt()
    .user(prompt)
    .toolCallbacks(mcpTools.getToolCallbacks())
    .call()
    .content();
return new McpChatResult(content, toolNames);`,
    },
  ],
  frontend: [
    {
      label: '调用 MCP Chat',
      language: 'tsx',
      code: `const data = await postJson<McpChatResponse>(
  \`\${API_BASE}/mcp/chat\`,
  { prompt, provider },
)
setResult(data) // content + toolNames`,
    },
  ],
}
