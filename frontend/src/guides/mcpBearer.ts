import type { SampleGuideData } from './types'

/** MCP Bearer 鉴权样例讲解（第九期）。 */
export const mcpBearerGuide: SampleGuideData = {
  title: 'MCP Bearer',
  concepts: [
    'mcp-server /mcp 校验 Authorization: Bearer；无/错 → 401。',
    '主应用 remote Client 自动带 MCP_BEARER_TOKEN（默认 dev-mcp-token）。',
    '两端密钥须一致；inprocess 不走 HTTP，不要求 Bearer。',
    'Playground 无登录框：配 env 后用 remote 列工具 / 聊天验证。',
  ],
  logic: {
    title: '鉴权对照',
    problem: '第六期 Streamable HTTP 本地明文，生产面缺最小凭证约定。',
    purpose: '用共享密钥演示 Server 校验与 Client 带凭证，不引入 OAuth 产品面。',
    pros: ['开箱同默认 token', '与 inprocess 对照清晰', '401 文案可定位密钥不一致'],
    cons: ['非 OAuth/JWT', '无多用户 ACL'],
    scenarios: ['本地联调旁进程 MCP', '拷进业务前先理解 HTTP 鉴权落点'],
    steps: [
      { title: '起 mcp-server', detail: '默认校验 Bearer；可 export MCP_BEARER_TOKEN。' },
      { title: '主应用 remote', detail: 'Client 注入同密钥；GET /mcp/tools 应列出工具。' },
      { title: '对照 inprocess', detail: '切 inprocess 无需 8081 / Bearer。' },
    ],
  },
  backend: [
    {
      label: 'Server Filter',
      language: 'java',
      code: `// McpBearerAuthFilter：/mcp 无 Bearer → 401
MessageDigest.isEqual(expected, actual)`,
    },
    {
      label: 'Client Customizer',
      language: 'java',
      code: `transportBuilder.httpRequestCustomizer((b, m, u, body, ctx) ->
  b.setHeader("Authorization", "Bearer " + token));`,
    },
  ],
  frontend: [
    {
      label: 'remote 验证',
      language: 'tsx',
      code: `await getJson(\`\${API_BASE}/mcp/tools\`)
// 401/503：检查两端 MCP_BEARER_TOKEN`,
    },
  ],
}
