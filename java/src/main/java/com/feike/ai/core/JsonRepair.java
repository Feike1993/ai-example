package com.feike.ai.core;

/**
 * 修复 LLM 常见 JSON 瑕疵：Markdown 围栏、字符串内未转义引号。
 * <p>
 * 抽成无 Spring 依赖的纯函数，便于单测和拷进其他项目。
 */
public final class JsonRepair {

    private JsonRepair() {}

    /**
     * 去掉 {@code ```json ... ```} 围栏；模型常把 JSON 包在 Markdown 里导致解析失败。
     *
     * @param content 模型原文，{@code null} 视为空串
     * @return 去掉围栏后的文本
     */
    public static String stripMarkdownFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNl = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNl > 0 && lastFence > firstNl) {
            return trimmed.substring(firstNl + 1, lastFence).trim();
        }
        return trimmed;
    }

    /**
     * 修复 JSON 字符串值内未转义的引号。
     * 例如 {@code {"name":"他说"你好"}} → {@code {"name":"他说\"你好"}}。
     *
     * @param content 可能非法的 JSON 文本
     * @return 尽量可解析的文本；无法判断时保持原样
     */
    public static String repairUnescapedQuotes(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }
            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    /** 跳过空白后若是 , } ] :，更像键/值结束符，否则当作字符串里的裸引号。 */
    private static boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }
}
