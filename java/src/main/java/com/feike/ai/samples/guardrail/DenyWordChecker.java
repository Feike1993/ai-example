package com.feike.ai.samples.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性敏感词检查：子串命中即失败（大小写不敏感）。
 */
public final class DenyWordChecker {

    private DenyWordChecker() {}

    /**
     * @param text  待检查文本
     * @param words 敏感词表
     * @return 命中的第一个词；未命中为 {@code null}
     */
    public static String firstHit(String text, List<String> words) {
        if (text == null || text.isBlank() || words == null || words.isEmpty()) {
            return null;
        }
        String hay = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            if (hay.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return null;
    }

    /**
     * @param text  待检查文本
     * @param words 敏感词表
     * @return 是否命中
     */
    public static boolean contains(String text, List<String> words) {
        return firstHit(text, words) != null;
    }

    /**
     * 规范化词表副本。
     *
     * @param words 配置词表
     * @return 非空 trim 后的列表
     */
    public static List<String> normalize(List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                out.add(word.trim());
            }
        }
        return List.copyOf(out);
    }
}
