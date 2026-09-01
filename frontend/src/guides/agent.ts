import type { SampleGuideData } from './types'

/** Agent Loop 样例讲解：显式 ReAct vs 框架托管 + 底层逻辑。 */
export const agentGuide: SampleGuideData = {
  title: 'Agent Loop',
  concepts: [
    'Agent = LLM + Planning + Memory + Tools；第一期 Memory 只用当轮消息列表。',
    '循环：Perceive（用户输入）→ Reason（是否调工具）→ Act（执行）→ Observe（结果回消息）。',
    '终止：模型不再返回 tool_calls、达到 maxSteps，或工具异常返回错误文本让模型降级。',
    '显式 Loop 适合审计每一步；Framework（ChatClient.tools）适合业务快速接入。',
    'SSE 终答：工具轮同步完成后先推 steps，再推 finalAnswer；Reactor 里「一次准备结果」用 Mono，「多个 token」用 Flux。',
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
        title: 'Mono = 0～1 个结果的 Publisher',
        detail:
          'Reactor 里 Mono 表示「最多发出一个元素就结束」（成功一个值、空、或错误）。底层仍是订阅驱动：没有人 subscribe，流水线不会跑。SSE 接口返回的是 Flux（多个 SSE 事件），但「工具轮准备」这一步只产出一份 StreamPrep，所以用 Mono 建模更贴切。',
      },
      {
        title: '为什么选 Mono，而不是直接阻塞或 Flux',
        detail:
          'prepareStream 里有多次同步 LLM call + 本地执行工具，可能耗时数秒。若在处理 SSE 的线程上直接跑，会占住事件循环/请求线程。用 Mono 把「算出那一份 StreamPrep」声明成异步步骤后，Controller 再 flatMapMany 拼出 steps 事件 + 终答 Flux。不用 Flux.fromCallable：工具准备结果只有一份，不是一串元素；Reactor 3.8 也已去掉 Flux.fromCallable，习惯上阻塞型单次计算用 Mono.fromCallable。',
      },
      {
        title: 'Mono.fromCallable + subscribeOn 在做什么',
        detail:
          'fromCallable：把一段同步代码包成「被订阅时才执行」的延迟计算（懒执行），算完把返回值作为 Mono 的唯一元素发出。subscribeOn(boundedElastic)：指定这段 callable 跑在弹性线程池，避免阻塞 Netty/Web 事件循环。订阅发生后：elastic 线程跑 prepareStream → 发出 StreamPrep → flatMapMany 转成 SSE Flux（先 steps，再 answer token）。',
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
    {
      label: 'SSE 准备 — Mono.fromCallable',
      language: 'java',
      code: `/** 工具轮可能阻塞数秒：包成 Mono，订阅时再跑，并丢到弹性线程池。 */
public Mono<ReactAgentLoop.StreamPrep> prepareReactStream(...) {
    return Mono.fromCallable(() ->
            ReactAgentLoop.prepareStream(chatModel, demoTools, systemPrompt, prompt, steps))
        .subscribeOn(Schedulers.boundedElastic());
}

// Controller：一份 StreamPrep → 多个 SSE 事件
return prepareReactStream(...)
    .flatMapMany(prep -> Flux.concat(
        Flux.just(stepsEvent),   // event:steps
        answerEvents             // finalAnswer 增量（Flux）
    ));`,
    },
  ],
  frontend: [
    {
      label: 'ReAct vs Framework 分流',
      language: 'tsx',
      code: `const body = { prompt, provider, ...(mode === 'react' ? { maxSteps } : {}) }

if (mode === 'react' && transport === 'sse') {
  streamAgentReact(prompt, provider, maxSteps, onSteps, onChunk, onDone, onError)
} else if (mode === 'react') {
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
