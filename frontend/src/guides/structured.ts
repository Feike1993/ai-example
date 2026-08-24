import type { SampleGuideData } from './types'

/** 结构化输出样例讲解：Bean 转换 + 修复重试。 */
export const structuredGuide: SampleGuideData = {
  title: '结构化输出',
  concepts: [
    'JSON Mode：只保证是 JSON，不保证字段。',
    'Schema / Bean 转换：按类型解析；失败就要修或重试。',
    'LLM 常见瑕疵：Markdown 围栏、字符串内未转义引号、前后解释文字。',
    '本仓三层策略：本地修 JSON → 重试时追加严格指令和上次错误 → 仍失败再抛错。',
    '业务侧必须用不挂工具的 ChatClient，避免 tool 消息污染 JSON。',
  ],
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
