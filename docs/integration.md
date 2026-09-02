# 如何拷进业务项目

每个样例都是独立类，不要整仓复制。按需拷文件，再接到你项目已有的配置和异常体系。

## 通用准备

1. 依赖：Spring AI BOM `2.0.0` + `spring-ai-starter-model-openai`
2. 配置：`app.ai.providers` 多网关 + `default-provider`；RAG 另配 Embedding（本仓用 DashScope）
3. Prompt 放 `resources/prompts/`，经 `PromptLoader` 加载；不要把长 prompt 写进 Service

### PromptLoader

拷 `core/PromptLoader.java` 与 `resources/prompts/*.st`。启动时在 Service 构造器里 `promptLoader.load("chat-assistant.st")`；需要动态片段时用 `load("xxx.st", Map.of("key", value))` 替换 `{key}` 占位符。

| 模板 | 用途 |
| --- | --- |
| `chat-assistant.st` | Chat 同步 / 流式 |
| `agent-react.st` | Agent ReAct / framework |
| `extract-ticket.st` | 结构化工单 |
| `multiagent-*.st` | 多 Agent 编排 / 调研 / 执笔 |

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

- **Server**：独立进程更常见；可直接参考仓库根目录 [`mcp-server/`](../mcp-server/)（STREAMABLE `/mcp`，端口 8081）
- **Client**：业务应用加 `spring-ai-starter-mcp-client`，配置远端 `url`，把 `ToolCallbackProvider` 挂到 ChatClient
- 本仓默认 `app.ai.mcp.mode=remote`；`inprocess` 保留二期同进程学习路径。HTTP MCP 鉴权见 [19-mcp-bearer.md](samples/19-mcp-bearer.md)（`MCP_BEARER_TOKEN`）

### RAG

- 拷 `samples.rag` + `spring-ai-starter-vector-store-pgvector`
- **Embedding 与 Chat Provider 分离**：很多聊天网关没有 Embedding；本仓用 `app.ai.embedding-provider=dashscope`
- 先保证 ingest 幂等与 `sources` 回传，再考虑混合检索 / 查询改写 / HyDE

## 第三期

### 上下文工程

- 拷 `samples.context`：`ChatSessionStore` + trim / summarize
- 默认 `JdbcChatSessionStore`（PostgreSQL）；`app.ai.context.store=memory` 可回退进程内
- 精确 tokenizer 见 [backlog](backlog.md)

### 多 Agent

- 拷 `samples.multiagent`：Orchestrator + 专员（工具 / 执笔）
- 分布式与评测看板见 [backlog](backlog.md)；不要一上来上独立工作流引擎

### 第四期

#### Hybrid RAG

- 拷 `RrfFusion`、`RagKeywordRetriever`，扩展 `RagSampleService`
- ingest 后确保 PG 全文 GIN 索引；`retrievalMode=hybrid` 时 RRF 融合
- compare API 便于 UI 对照 vector / hybrid

#### Agent 评测

- 拷 `samples.eval` + `eval/golden/*.json`
- `POST /eval/run` 复用现有 Service；断言 mustContain / expectToolName / expectSources
- 不做评测看板；Token 用量汇总依赖 v0.2.0 的 `TokenUsage`

Python 对照：`hybrid_rag.py`、`eval_runner.py`。

### 第五期

#### 持久会话

- 拷 `ChatSessionStore` / `JdbcChatSessionStore`；与 RAG 同库
- 接口形状兼容第三期 `/context/*`

#### 长期记忆

- 拷 `samples.memory`；`corpus=long-term-memory` 与 RAG 演示语料隔离
- remember / recall / chat / extract；依赖 Embedding + pgvector

Python 对照：`context_memory.py`（可选 SQLite）、`long_term_memory.py`、`memory_extract.py`、`memory_recall_compare.py`。

### 第六期

#### MCP 远端拆分

- 拷 [`mcp-server/`](../mcp-server/) + 主应用 `spring.ai.mcp.client` 配置
- `app.ai.mcp.mode=remote|inprocess`；Bearer 见 [19-mcp-bearer.md](samples/19-mcp-bearer.md)

#### 完整 HyDE

- 扩展 `RagSampleService`：`queryExpansion=none|rewrite|hyde`
- 假想段落只用于检索；`POST /rag/query/compare-expansion` 对照命中
- 配置 `app.ai.rag.hyde.enabled` / `fuse-with-original`

Python 对照：`mcp_client_http.py`、`hyde_rag.py`。

### 第七期

#### 语义分块 / 父子文档

- 拷 `SemanticMarkdownSplitter`；ingest `strategy` + 旁路 corpus
- 父子：子块 metadata 含 `parentText`；`expandParents` 拼上下文
- `POST /rag/query/compare-chunking`

Python：`semantic_chunk.py`、`parent_child_rag.py`。

刻意不做总表：[backlog.md](backlog.md)。
