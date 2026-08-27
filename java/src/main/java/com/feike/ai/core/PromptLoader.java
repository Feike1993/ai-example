package com.feike.ai.core;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 从 classpath {@code prompts/} 加载 StringTemplate 风格模板，支持 {@code {key}} 占位符。
 */
@Component
public class PromptLoader {

    private static final String PROMPTS_PREFIX = "classpath:prompts/";

    private final ResourceLoader resourceLoader;
    private final PropertyPlaceholderHelper placeholderHelper =
        new PropertyPlaceholderHelper("{", "}");

    /**
     * @param resourceLoader 解析 classpath 资源
     */
    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 加载无占位符的模板。
     *
     * @param fileName 文件名，如 {@code chat-assistant.st}
     * @return 模板正文（首尾空白已去除）
     * @throws IOException 文件不存在或读取失败
     */
    public String load(String fileName) throws IOException {
        return load(fileName, Map.of());
    }

    /**
     * 加载模板并用 {@code placeholders} 替换 {@code {key}}。
     *
     * @param fileName       文件名
     * @param placeholders   占位符键值；缺失键保留原样
     * @return 替换后的正文（首尾空白已去除）
     * @throws IOException 文件不存在或读取失败
     */
    public String load(String fileName, Map<String, String> placeholders) throws IOException {
        Resource resource = resourceLoader.getResource(PROMPTS_PREFIX + fileName);
        if (!resource.exists()) {
            throw new IOException("Prompt 模板不存在: " + fileName);
        }
        String template = resource.getContentAsString(StandardCharsets.UTF_8);
        if (placeholders.isEmpty()) {
            return template.trim();
        }
        return placeholderHelper.replacePlaceholders(template, placeholders::get).trim();
    }
}
