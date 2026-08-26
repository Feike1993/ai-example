# 如何拷进业务项目

每个样例都是独立类，不要整仓复制。按需拷文件，再接到你项目已有的配置和异常体系。

## 通用准备

1. 依赖：Spring AI BOM `2.0.0` + `spring-ai-starter-model-openai`
2. 配置：`app.ai.providers` 多网关 + `default-provider`；RAG 另配 Embedding（本仓用 DashScope）
3. Prompt 放 `resources/prompts/`，不要把长 prompt 写进 Service

## 第一期

### Chat

拷 `ApiPathResolver.java`、`LlmProviderRegistry.java`、`AiProperties.java`。

### 结构化输出

拷 `JsonRepair.java`、`StructuredOutputInvoker.java`。必须用 **plain** ChatClient（不要挂 Tools）。

### Tool Calling

拷 `@Tool` + `.tools(bean)`；按场景挂载，不全局一把梭。

### Agent Loop

- 快速接入：`ChatClient.tools(...).call()`
- 要审计步数：拷 `ReactAgentLoop.java`

## 第二期

### MCP

- **Server**：独立进程更常见；拷 `samples.mcp` 里 Server 侧（`@McpTool` / ToolCallback 注册）+ `spring-ai-starter-mcp-server-webmvc`，协议用 `STREAMABLE`
- **Client**：业务应用只加 `spring-ai-starter-mcp-client`，配置远端 `url`，把 `ToolCallbackProvider` 挂到 ChatClient
- 本仓为了学习把 Server/Client 放同一 Boot 进程；拷进生产时请拆开，并给 HTTP MCP 加鉴权

### RAG

- 拷 `samples.rag` + `spring-ai-starter-vector-store-pgvector`
- **Embedding 与 Chat Provider 分离**：很多聊天网关没有 Embedding；本仓用 `app.ai.embedding-provider=dashscope`
- 先保证 ingest 幂等与 `sources` 回传，再考虑混合检索 / 查询改写

Python 对照用于理解协议；JVM 业务优先集成 Java 代码。
