package com.feike.ai.samples.guardrail.service.impl;

import com.feike.ai.core.guardrail.DenyWordChecker;

import com.feike.ai.samples.guardrail.service.GuardrailSampleService;
import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.PromptLoader;
import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.core.TokenUsageExtractor;
import com.feike.ai.samples.structured.service.StructuredOutputInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 输出护栏样例：输入/输出敏感词 + 可选结构信封；失败短路并返回逐步 checks。
 * <p>
 * 确定性规则优先；LLM 只在输入通过后调用，不静默改写违规内容。
 */
@Service
public class GuardrailSampleServiceImpl implements GuardrailSampleService {

    /** 护栏拒答固定文案。 */
    public static final String BLOCKED_REFUSAL =
        "请求或回复未通过护栏检查，已拒绝生成/展示内容。请修改表述后重试。";

    private static final String STRUCTURE_SYSTEM = """
        你是助手。必须返回 JSON 对象，字段：
        - safe: 布尔，内容是否可安全展示（正常问答为 true）
        - content: 字符串，给用户看的简体中文回复
        不要输出 Markdown 代码块或其它字段。
        """;

    private final LlmProviderRegistry registry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final List<String> denyWords;
    private final String chatSystemPrompt;

    /**
     * @param registry                 Provider
     * @param properties               读取 deny-words
     * @param structuredOutputInvoker  可选结构信封
     * @param promptLoader             复用 chat system prompt
     */
    public GuardrailSampleServiceImpl(
        LlmProviderRegistry registry,
        AiProperties properties,
        StructuredOutputInvoker structuredOutputInvoker,
        PromptLoader promptLoader
    ) throws IOException {
        this.registry = registry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.denyWords = DenyWordChecker.normalize(properties.guardrail().denyWords());
        this.chatSystemPrompt = promptLoader.load("chat-assistant.st");
    }




    /**
     * 跑完整护栏管道。
     *
     * @param prompt            用户输入
     * @param provider          Provider id
     * @param requireStructured 是否强制 SafeEnvelope
     * @return 结果含 checks
     */
    public GuardrailResult chat(String prompt, String provider, boolean requireStructured) {
        List<Check> checks = new ArrayList<>();

        String inputHit = DenyWordChecker.firstHit(prompt, denyWords);
        if (inputHit != null) {
            checks.add(new Check("input_deny", false, "命中敏感词: " + inputHit));
            return new GuardrailResult(true, "input_deny", BLOCKED_REFUSAL, List.copyOf(checks), null);
        }
        checks.add(new Check("input_deny", true, "未命中输入词表"));

        ChatClient client = registry.plainClient(provider);
        TokenUsageDTO usage;
        String content;

        // 信封
        if (requireStructured) {
            try {
                StructuredOutputInvoker.InvokeResult<SafeEnvelope> invoke =
                    structuredOutputInvoker.invoke(client, STRUCTURE_SYSTEM, prompt, SafeEnvelope.class);
                usage = invoke.usage();
                SafeEnvelope envelope = invoke.value();
                if (envelope == null || !envelope.safe()
                    || envelope.content() == null || envelope.content().isBlank()) {
                    checks.add(new Check(
                        "structure",
                        false,
                        envelope == null ? "信封为空" : "safe=false 或 content 为空"
                    ));
                    return new GuardrailResult(true, "structure", BLOCKED_REFUSAL, List.copyOf(checks), usage);
                }
                checks.add(new Check("structure", true, "信封解析成功且 safe=true"));
                content = envelope.content();
            } catch (RuntimeException ex) {
                checks.add(new Check("structure", false, "解析失败: " + abbreviate(ex.getMessage())));
                return new GuardrailResult(true, "structure", BLOCKED_REFUSAL, List.copyOf(checks), null);
            }
        } else {
            // Spring AI：CallResponseSpec 只能消费一次；勿对 content()/chatResponse() 连调或重复 content()
            var chatResponse = client.prompt().system(chatSystemPrompt).user(prompt).call().chatResponse();
            content = textFrom(chatResponse);
            usage = TokenUsageExtractor.from(chatResponse);
            checks.add(new Check("structure", true, "未启用结构校验（跳过）"));
        }

        String outputHit = DenyWordChecker.firstHit(content, denyWords);
        if (outputHit != null) {
            checks.add(new Check("output_deny", false, "命中敏感词: " + outputHit));
            return new GuardrailResult(true, "output_deny", BLOCKED_REFUSAL, List.copyOf(checks), usage);
        }
        checks.add(new Check("output_deny", true, "未命中输出词表"));

        return new GuardrailResult(false, null, content, List.copyOf(checks), usage);
    }

    private static String textFrom(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 120 ? message : message.substring(0, 120) + "…";
    }
}
