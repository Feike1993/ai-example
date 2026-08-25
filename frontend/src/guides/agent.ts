import type { SampleGuideData } from './types'

/** Agent Loop 样例讲解：显式 ReAct vs 框架托管 + 底层逻辑。 */
export const agentGuide: SampleGuideData = {
  title: 'Agent Loop',
  concepts: [
    'Agent = LLM + Planning + Memory + Tools；第一期 Memory 只用当轮消息列表。',
    '循环：Perceive（用户输入）→ Reason（是否调工具）→ Act（执行）→ Observe（结果回消息）。',
    '终止：模型不再返回 tool_calls、达到 maxSteps，或工具异常返回错误文本让模型降级。',
    '显式 Loop 适合审计每一步；Framework（ChatClient.tools）适合业务快速接入。',
  ],
  logic: {
    title: 'Agent Loop 底层逻辑',
    steps: [
      {
        title: 'Agent = 会多步决策的 Chat + Tools',
        detail:
          '相对单次 Tool Calling，Agent 在任务「未完成」时会继续 Reason → Act，直到给出最终答案或触达终止条件。',
      },
      {
        title: 'Perceive → Reason → Act → Observe',
        detail:
          '用户输入进消息列表；模型判断要不要工具；执行工具；观察结果写回消息，再进入下一轮推理。',
      },
      {
        title: 'Memory 第一期 = 当轮消息列表',
        detail:
          '无跨会话外存；每一步都基于不断变长的 messages，历史工具结果也在同一列表里。',
      },
      {
        title: '终止条件要显式',
        detail:
          '无 tool_calls（任务完成）、达到 maxSteps（防死循环）、工具错误文本让模型降级，而不是无限重试同一调用。',
      },
      {
        title: '显式 Loop vs Framework',
        detail:
          '手写 Loop（如 ReactAgentLoop）可审计每一步；ChatClient.tools 托管适合业务快速接入，轨迹不如显式清晰。',
      },
      {
        title: 'Agent ≠ 工作流',
        detail:
          '工作流路径预先写死；Agent 下一步由模型选择。开放任务用 Agent，确定性任务用工作流。',
      },
    ],
  },
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
