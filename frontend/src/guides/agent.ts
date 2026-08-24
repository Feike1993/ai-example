import type { SampleGuideData } from './types'

/** Agent Loop 样例讲解：显式 ReAct vs 框架托管。 */
export const agentGuide: SampleGuideData = {
  title: 'Agent Loop',
  concepts: [
    'Agent = LLM + Planning + Memory + Tools；第一期 Memory 只用当轮消息列表。',
    '循环：Perceive（用户输入）→ Reason（是否调工具）→ Act（执行）→ Observe（结果回消息）。',
    '终止：模型不再返回 tool_calls、达到 maxSteps，或工具异常返回错误文本让模型降级。',
    '显式 Loop 适合审计每一步；Framework（ChatClient.tools）适合业务快速接入。',
  ],
  backend: [
    {
      label: '显式 ReAct — ReactAgentLoop.run',
      language: 'java',
      code: `for (int i = 1; i <= limit; i++) {
    ChatResponse response = chatModel.call(new Prompt(messages, toolOptions(callbacks)));
    AssistantMessage assistant = response.getResult().getOutput();
    messages.add(assistant);

    List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
    if (toolCalls == null || toolCalls.isEmpty()) {
        return new Trace(textOf(assistant), List.copyOf(steps), false);
    }

    List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
    for (AssistantMessage.ToolCall call : toolCalls) {
        String result = executeTool(byName, call); // 失败返回错误字符串
        steps.add(new Step(i, textOf(assistant), call.name(), call.arguments(), result));
        toolResponses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
    }
    messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
}
return new Trace("已达到最大步数 " + limit + "，已停止以防无限循环。",
    List.copyOf(steps), true);`,
    },
  ],
  frontend: [
    {
      label: 'ReAct vs Framework 分流',
      language: 'tsx',
      code: `const body = { prompt, provider, ...(mode === 'react' ? { maxSteps } : {}) }

if (mode === 'react') {
  const data = await postJson<AgentTrace>(\`\${API_BASE}/agent/react\`, body)
  setTrace(data) // 含 finalAnswer、steps[]、reachedMaxSteps
} else {
  const data = await postJson<FrameworkResponse>(
    \`\${API_BASE}/agent/framework\`,
    body,
  )
  setFramework(data) // 仅 { content }
}`,
    },
  ],
}
