# 学习路径（第一期）

第一期覆盖 **LLM 调用** 和 **Agent 基础**。MCP / RAG / 上下文工程 / 多 Agent 见 [phase2.md](phase2.md)。

## 建议顺序

1. **Chat**（[samples/01-chat.md](samples/01-chat.md)）
   - Token、上下文窗口、temperature、流式与 TTFT
   - Java：`ChatClient.prompt().call()` / `.stream()`
   - Python：`openai.chat.completions`
2. **结构化输出**（[samples/02-structured.md](samples/02-structured.md)）
   - JSON Mode vs Schema；失败后本地修 JSON 再重试
   - Java：`StructuredOutputInvoker`
3. **Tool Calling**（[samples/03-tools.md](samples/03-tools.md)）
   - JSON Schema 定义工具、并行调用、工具粒度（原子操作）
   - 工具和 ChatClient 解耦，按场景挂载，不要全局一把梭
4. **ReAct Agent Loop**（[samples/04-agent.md](samples/04-agent.md)）
   - Perceive → Reason → Act → Observe
   - 终止条件：任务完成 / maxSteps / 工具错误降级
   - 对比「手写 Loop」和「框架托管」（Spring AI tool-calling / LangGraph `create_react_agent`）

## Agent vs 工作流

- **工作流**：路径预先写死（if/else、状态机）
- **Agent**：下一步由模型决定（要不要调工具、调哪个）
- 确定性任务用工作流；开放任务用 Agent，并用 maxSteps 防止无限循环
