import type { SampleGuideData } from './types'

/** MCP 样例讲解：协议层工具；默认 remote，面板可运行时切 inprocess。 */
export const mcpGuide: SampleGuideData = {
  title: 'MCP',
  concepts: [
    'Function Calling 是 LLM 能力；MCP 是工具接入协议；Agent 是 Loop + Tools 的系统概念。',
    '默认 app.ai.mcp.mode=remote（仅初始值）：主应用作 Client，连 mcp-server:8081 的 Streamable HTTP `/mcp`。',
    '面板 / PUT /mcp/mode 可在 remote ↔ inprocess 间运行时切换（内存、不持久化）；启动不强连 8081。',
    'mode=inprocess：同进程 MethodToolCallbackProvider 直挂 ChatClient，无需旁进程。',
    'stdio 适合本地子进程；Streamable HTTP 适合远程/生产。',
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
        title: '远端与同进程并存',
        detail:
          '主应用同时备好 SyncMcpToolCallbackProvider 与 mcpServerTools；按运行时 mode 选用。remote 需先起 mcp-server（8081）。',
      },
      {
        title: '对照本地 Tool Calling',
        detail:
          'POST /tools 是进程内 .tools(bean)；POST /mcp/chat 的 toolNames 来自当前 mode 的工具源。',
      },
    ],
  },
  backend: [
    {
      label: '软启动 Client — application.yml',
      language: 'yaml',
      code: `app.ai.mcp.mode: remote  # 初始值，可被 PUT /mcp/mode 覆盖
spring.ai.mcp.client.enabled: true
spring.ai.mcp.client.initialized: false  # 启动不连 8081
spring.ai.mcp.client.streamable-http.connections.demo.url: http://localhost:8081`,
    },
    {
      label: '样例聊天 — McpSampleService',
      language: 'java',
      code: `// remote → SyncMcpToolCallbackProvider（懒 initialize）
// inprocess → @Qualifier("mcpServerTools")
.tools(resolveTools())`,
    },
  ],
  frontend: [
    {
      label: '切换模式 + 调用 Chat',
      language: 'tsx',
      code: `await putJson(\`\${API_BASE}/mcp/mode\`, { mode: 'inprocess' })
const data = await postJson<McpChatResponse>(
  \`\${API_BASE}/mcp/chat\`,
  { prompt, provider },
)
// content + toolNames + mode`,
    },
  ],
}
