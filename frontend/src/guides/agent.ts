import type { SampleGuideData } from './types'

/** Agent Loop 样例讲解：显式 ReAct vs 框架托管 + 底层逻辑。 */
export const agentGuide: SampleGuideData = {
  title: 'Agent Loop',
  concepts: [
    'Agent = LLM + Planning + Memory + Tools；第一期 Memory 只用当轮消息列表。',
    '循环：Perceive（用户输入）→ Reason（是否调工具）→ Act（执行）→ Observe（结果回消息）。',
    '终止：模型不再返回 tool_calls、达到 maxSteps，或工具异常返回错误文本让模型降级。',
    '显式 Loop 适合审计每一步；Framework（ChatClient.tools）适合业务快速接入。',
    '第十期：同步与流式共用完整多跳；SSE 推 tool_call/tool_result + usage/done（见进阶 AgentToolSse）。',
  ],
  logic: {
    title: 'Agent Loop 底层逻辑',
    problem: '单次 Tool Calling 只能走一步；开放任务需要多轮「推理→行动→观察」直到完成或触达上限。',
    purpose: '在显式循环中反复调用模型与工具，直到无 tool_calls、达 maxSteps 或可降级终答。',
    pros: [
      '适合目标明确但路径开放的任务。',
      '显式 Loop 可审计每一步工具轨迹。',
      '终止条件可防死循环。',
    ],
    cons: [
      '步数与延迟、费用随任务复杂度上升。',
      '模型选错工具会导致绕路或失败。',
      '不如预写死工作流那样确定、易测。',
    ],
    scenarios: [
      '调研、排障、多步查询后再总结。',
      '需要中途根据工具结果改策略的助手。',
    ],
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
      {
        title: '流式 = 同一 run + Progress（第十期）',
        detail:
          'A4 曾用 prepareStream 只跑一轮工具再流终答。【已过时：现改为 Progress 回调，与同步共用完整多跳】阻塞循环放在 Flux.create + boundedElastic，边执行边推 SSE，避免占住事件循环。',
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
    usageAcc = TokenUsageExtractor.sum(usageAcc, TokenUsageExtractor.from(response));

    List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
    if (toolCalls == null || toolCalls.isEmpty()) {
        return new Trace(textOf(assistant), List.copyOf(steps), false, usageAcc, usageCalls);
    }

    for (AssistantMessage.ToolCall call : toolCalls) {
        progress.onToolCall(i, textOf(assistant), call.name(), call.arguments());
        String result = executeTool(byName, call);
        progress.onToolResult(i, call.name(), result);
        steps.add(new Step(i, textOf(assistant), call.name(), call.arguments(), result));
    }
    // … ToolResponseMessage 写回 messages
}
return new Trace("已达到最大步数…", List.copyOf(steps), true, usageAcc, usageCalls);`,
    },
    {
      label: 'SSE — Flux.create + Progress',
      language: 'java',
      code: `return Flux.<ServerSentEvent<String>>create(sink -> {
    ReactAgentLoop.Progress progress = new ReactAgentLoop.Progress() {
        public void onToolCall(...) { sink.next(namedEvent("tool_call", …)); }
        public void onToolResult(...) { sink.next(namedEvent("tool_result", …)); }
    };
    Trace trace = ReactAgentLoop.run(..., progress);
    sink.next(namedEvent("steps", …)); // 可选兼容
    sink.next(ServerSentEvent.builder().data(trace.finalAnswer()).build());
    sink.next(namedEvent("usage", …));
    sink.next(namedEvent("done", Map.of("reachedMaxSteps", trace.reachedMaxSteps())));
    sink.complete();
}).subscribeOn(Schedulers.boundedElastic());`,
    },
  ],
  frontend: [
    {
      label: 'ReAct vs Framework 分流',
      language: 'tsx',
      code: `const body = { prompt, provider, ...(mode === 'react' ? { maxSteps } : {}) }

if (mode === 'react' && transport === 'sse') {
  streamAgentReact(prompt, provider, maxSteps, {
    onToolCall, onToolResult, onChunk, onUsage, onDone, onComplete, onError,
  })
} else if (mode === 'react') {
  const data = await postJson<AgentTrace>(\`\${API_BASE}/agent/react\`, body)
  setTrace(data) // finalAnswer、steps[]、usage、usageCalls
} else {
  const data = await postJson<FrameworkResponse>(
    \`\${API_BASE}/agent/framework\`,
    body,
  )
  setFramework(data)
}`,
    },
  ],
}
