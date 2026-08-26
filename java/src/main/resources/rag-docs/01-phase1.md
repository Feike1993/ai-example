# 项目导读：ai-example 第一期

本仓库是 AI Agent 学习 cookbook，不是业务系统。

第一期覆盖四个概念：

1. **Chat**：Token、采样温度、SSE 与 TTFT（首 token 时间）
2. **结构化输出**：JSON Schema、围栏剥离、失败重试与修复 prompt
3. **Tool Calling**：模型只输出调用意图，由应用执行 `@Tool`
4. **Agent Loop**：显式 ReAct 步数熔断，以及框架自动 tool-calling

Java 使用 Spring Boot + Spring AI；Python 用 OpenAI SDK / LangGraph 做对照。
HTTP 统一挂在 context-path `/ai-example` 下。
