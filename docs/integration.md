# 如何拷进业务项目

每个样例都是独立类，不要整仓复制。按需拷文件，再接到你项目已有的配置和异常体系。

## 通用准备

1. 依赖：Spring AI BOM `2.0.0` + `spring-ai-starter-model-openai`（或你项目已有的等价 starter）
2. 配置：`app.ai.providers` 多网关 + `default-provider`（默认 `deepseek`），本仓用 `PROVIDER_DEEPSEEK_API_KEY` / `AI_API_KEY`
3. Prompt 放 `resources/prompts/`，不要把长 prompt 写进 Service

## 按样例拷贝

### Chat

拷 `ApiPathResolver.java`、`LlmProviderRegistry.java`、`AiProperties.java`。

业务里若已有自己的 ChatClient 工厂，只拷调用方式即可，不必再引入本仓工厂。

### 结构化输出

拷 `JsonRepair.java`、`StructuredOutputInvoker.java`。

接到业务后：

- 用项目的异常类型替换 `IllegalStateException`
- 需要指标时再加 Micrometer
- 出题 / 评分等场景必须用 **plain** ChatClient（不要挂 Tools），否则 JSON 会被 tool call 消息污染

### Tool Calling

拷 `DemoTools` 的写法：`@Tool` + `.tools(bean)`。

把模拟天气换成真实服务；保持工具无副作用或做好幂等。不要把所有工具挂到全局 ChatClient。

### Agent Loop

- 只要「模型自己决定调不调工具」：用 Spring AI `ChatClient.tools(...).call()`（`/agent/framework`）
- 要审计每一步、限制步数、工具失败降级：拷 `ReactAgentLoop.java`（`/agent/react`）

Python 对照用于理解协议；JVM 业务优先集成 Java 代码。
