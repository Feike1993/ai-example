import type { SampleGuideData } from './types'

/** 结构化输出样例讲解：Bean 转换 + 修复重试 + 底层逻辑。 */
export const structuredGuide: SampleGuideData = {
  title: '结构化输出',
  concepts: [
    'JSON Mode：只保证是 JSON，不保证字段。',
    'Schema / Bean 转换：按类型解析；失败就要修或重试。',
    'LLM 常见瑕疵：Markdown 围栏、字符串内未转义引号、前后解释文字。',
    '本仓三层策略：本地修 JSON → 重试时追加严格指令和上次错误 → 仍失败再抛错。',
    '业务侧必须用不挂工具的 ChatClient，避免 tool 消息污染 JSON。',
  ],
  logic: {
    title: '结构化输出底层逻辑',
    steps: [
      {
        title: '目标是「可解析的数据」',
        detail:
          '业务要的是字段齐全的对象（如工单 JSON），不是散文；自然语言回复无法直接进下游系统。',
      },
      {
        title: 'Schema / Bean 约束输出形状',
        detail:
          '把目标类型（字段、类型）编进提示或转换器格式；模型仍在生成文本，但被引导成符合结构的 JSON。',
      },
      {
        title: 'JSON Mode ≠ 字段正确',
        detail:
          '只保证返回的是 JSON，不保证键名或类型正确；真正落地靠 Schema 解析与校验。',
      },
      {
        title: 'LLM 输出常「差一点」',
        detail:
          '常见瑕疵：Markdown 围栏、未转义引号、前后解释文字。先本地修 JSON，再交给 Bean 转换。',
      },
      {
        title: '失败则带着错误重试',
        detail:
          '把上次解析错误写回 system、收紧指令；超过次数再抛错，避免静默脏数据进入业务。',
      },
      {
        title: '必须用 plain ChatClient',
        detail:
          '挂工具会混入 tool 消息，污染「只要 JSON」的假设；结构化抽取场景用不挂工具的客户端。',
      },
    ],
  },
  backend: [
    {
      label: '调用与重试 — StructuredOutputInvoker.invoke',
      language: 'java',
      code: `public <T> T invoke(ChatClient chatClient, String systemPrompt,
                    String userPrompt, Class<T> type) {
    BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
    String secured = systemPrompt + "\\n\\n" + converter.getFormat()
        + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

    Exception lastError = null;
    for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
        String attemptSystem = attempt == 1
            ? secured
            : buildRetrySystemPrompt(secured, lastError);
        try {
            return callStructured(chatClient, attemptSystem, userPrompt, converter);
        } catch (Exception e) {
            lastError = e;
        }
    }
    throw new IllegalStateException("结构化输出解析失败", lastError);
}`,
    },
    {
      label: '本地修复 — convertWithRepair',
      language: 'java',
      code: `private <T> T convertWithRepair(String content, BeanOutputConverter<T> converter) {
    String stripped = JsonRepair.stripMarkdownFence(content);
    try {
        return converter.convert(stripped);
    } catch (Exception firstError) {
        String repaired = JsonRepair.repairUnescapedQuotes(stripped);
        if (!repaired.equals(stripped)) {
            return converter.convert(repaired); // 本地修引号后再解析
        }
        throw firstError;
    }
}`,
    },
  ],
  frontend: [
    {
      label: '抽取工单 — POST /structured/ticket',
      language: 'tsx',
      code: `const run = async () => {
  setError(null)
  setTicket(null)
  setLoading(true)
  try {
    const data = await postJson<Ticket>(
      \`\${API_BASE}/structured/ticket\`,
      { text, provider },
    )
    setTicket(data)
  } catch (err) {
    setError(describeError(err))
  } finally {
    setLoading(false)
  }
}`,
    },
  ],
}
