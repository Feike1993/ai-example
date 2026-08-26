package com.feike.ai.samples.context;

/**
 * 上下文预算策略：裁剪旧轮次 vs 摘要压缩。
 */
public enum ContextStrategy {
    /** 丢弃最旧 user/assistant 对，保留最近轮次。 */
    TRIM,
    /** 将旧轮次压成摘要后再拼最近轮次。 */
    SUMMARIZE;

    /**
     * 解析请求中的策略名；空或未知时默认 trim。
     *
     * @param raw 请求字符串
     * @return 策略枚举
     */
    public static ContextStrategy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return TRIM;
        }
        return switch (raw.trim().toLowerCase()) {
            case "summarize", "summary" -> SUMMARIZE;
            default -> TRIM;
        };
    }
}
