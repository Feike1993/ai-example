# 模型与服务设置

教学场与工业场统一使用一套数据库模型配置，不再从 `application.yml`、`.env` 或
`PROVIDER_*` 环境变量读取 Java 模型参数。

## 唯一配置源

| 数据 | 存储位置 | 说明 |
| --- | --- | --- |
| Provider、基础地址、可用模型、温度、思考模式、代理策略 | `prod_model_provider` | 一个 Provider 可登记多个模型 |
| Chat、Vision、Embedding、ASR、TTS 默认路由 | `prod_model_route` | 每项能力选择一个 Provider 和模型；TTS 额外保存音色 |
| API Key | `prod_secret` | AES-256-GCM 密文；设置页只写不回显 |
| KEK | `PRODUCTION_KEK` | 只在环境变量中提供，禁止入库 |

`spring.ai.vectorstore.pgvector.dimensions` 仍是向量表结构参数，不属于可热切换的模型路由。
已有向量数据时不可随意修改维度。

## 启动与首次配置

1. 启动 PostgreSQL，并确保 Flyway V1–V7 已执行。
2. 生成 KEK：`openssl rand -base64 32`，写入 `.env` 的 `PRODUCTION_KEK`。
3. 启动 Java 和前端，登录工业级页面。
4. 打开“模型与服务设置”，填写 Provider API Key、可用模型及能力。
5. 分别保存 Chat、Vision、Embedding、ASR、TTS 路由。

设置页保存 Provider 或路由后会清空当前实例的模型缓存。教学接口 `/chat`、`/rag`、
`/agent` 等与工业接口 `/api/v1/**` 的下一次调用都会读取新配置。

## YAML 保留项

`app.ai.*` 只保留 RAG、MCP、上下文、Agent、记忆和护栏等教学行为参数；
`app.production.*` 只保留会话、限流、媒体大小、SSE 等工业行为参数。
两处都不再保存 Provider、模型名、采样温度或 API Key。

## Python 对照样例

`python/` 是独立进程的轻量对照实现，不属于浏览器中的“教学场景”，不会连接 Java 的
模型设置服务。它仍通过根目录 `.env` 中标注为“仅 Python”的变量运行。
