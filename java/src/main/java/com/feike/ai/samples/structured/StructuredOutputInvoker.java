package com.feike.ai.samples.structured;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.JsonRepair;
import com.feike.ai.core.PromptSecurityConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * 结构化输出调用与重试。
 * <p>
 * 三层策略：本地修 JSON → 重试时追加严格指令和上次错误 → 仍失败则抛异常。
 */
@Component
public class StructuredOutputInvoker {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputInvoker.class);

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
        """;

    private final AiProperties.Structured config;

    /**
     * @param properties 从中读取重试次数与修复开关
     */
    public StructuredOutputInvoker(AiProperties properties) {
        this.config = properties.structured();
    }

    /**
     * 调用 LLM 并把结果解析为 {@code type}；失败会按配置重试。
     *
     * @param chatClient   必须用不挂 Tools 的 Client，否则 tool 消息会污染 JSON
     * @param systemPrompt 任务说明；方法内会追加 schema 与防注入指令
     * @param userPrompt   用户原文，会包进 {@code <data-boundary>}
     * @param type         目标 Java 类型
     * @param <T>          解析后的类型
     * @return 解析成功的对象
     * @throws IllegalStateException 达到最大重试次数仍无法解析
     */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        Class<T> type
    ) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        // 1. 构建 Prompt
        String format = converter.getFormat();
        String secured = systemPrompt + "\n\n" + format + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        // 2. 重试
        log.debug("结构化解析开始，type={}", type.getName());

        Exception lastError = null;
        int maxAttempts = config.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptSystem = attempt == 1 ? secured : buildRetrySystemPrompt(secured, lastError);
            try {
                return callStructured(chatClient, attemptSystem, userPrompt, converter);
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    log.warn("结构化解析失败，准备重试: attempt={}/{}, error={}", attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("结构化解析失败，已达最大重试次数: attempts={}, error={}", maxAttempts, e.getMessage());
                }
            }
        }
        throw new IllegalStateException(
            "结构化输出解析失败: " + (lastError != null ? lastError.getMessage() : "unknown"),
            lastError
        );
    }

    /**
     * 不调 LLM，只对已有文本做围栏剥离 / 引号修复 / Bean 转换，供单测复用生产解析路径。
     *
     * @param raw  模型或夹具输出
     * @param type 目标类型
     * @param <T>  解析后的类型
     * @return 解析结果
     */
    public <T> T parse(String raw, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        return convertWithRepair(raw, converter);
    }

    /**
     * 调用 LLM 并把结果解析为 {@code type}。
     * @param chatClient 聊天客户端
     * @param systemPrompt 系统提示
     * @param userPrompt 用户提示
     * @param converter 转换器
     * @return 解析结果
     * @param <T>   解析后的类型
     */
    private <T> T callStructured(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        BeanOutputConverter<T> converter
    ) {
        var call = chatClient.prompt()
            .system(systemPrompt)
            .user("<data-boundary>\n" + userPrompt + "\n</data-boundary>")
            .call();
        if (config.schemaValidationEnabled()) {
            // 1. 模型输出直接带 schema，尝试直接解析
            return call.entity(converter, spec -> spec.validateSchema());
        }
        // 2. 模型输出不带 schema，尝试修复 JSON 后解析
        return convertWithRepair(call.content(), converter);
    }

    /**
     * 尝试剥离 Markdown 代码块 / 未转义引号修复 / Bean 转换。
     * @param content 原始内容
     * @param converter 转换器
     * @return 转换结果
     * @param <T>   解析后的类型
     */
    private <T> T convertWithRepair(String content, BeanOutputConverter<T> converter) {
        String stripped = JsonRepair.stripMarkdownFence(content);
        try {
            return converter.convert(stripped);
        } catch (Exception firstError) {
            String repaired = JsonRepair.repairUnescapedQuotes(stripped);
            if (!repaired.equals(stripped)) {
                try {
                    T result = converter.convert(repaired);
                    log.warn("结构化 JSON 存在未转义引号，已在本地修复后解析成功");
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 重试时构建新的系统消息，追加上次失败原因与严格 JSON 指令。
     * @param systemPrompt 原系统消息
     * @param lastError 上次失败原因
     * @return 新的系统消息
     */
    private String buildRetrySystemPrompt(String systemPrompt, Exception lastError) {
        if (!config.retryUseRepairPrompt()) {
            return systemPrompt;
        }
        StringBuilder prompt = new StringBuilder(systemPrompt).append("\n\n");
        if (config.retryAppendStrictJsonInstruction()) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");
        if (config.includeLastError() && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：").append(sanitize(lastError.getMessage()));
        }
        return prompt.toString();
    }

    /**
     * 对错误信息进行简单处理，避免过长或包含换行。
     * @param message 错误信息
     * @return 处理后的错误信息
     */
    private String sanitize(String message) {
        // 去除换行符并截断
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        int max = config.errorMessageMaxLength();
        if (oneLine.length() > max) {
            return oneLine.substring(0, max) + "...";
        }
        return oneLine;
    }
}
