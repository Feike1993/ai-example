package com.feike.ai.production.rag.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 生产检索的查询扩展。不含教学用的 {@code memory_rewrite} 与 compare 对照。
 */
public enum ProductionQueryExpansionEnum {

    NONE("none"),
    REWRITE("rewrite"),
    HYDE("hyde");

    private final String code;

    ProductionQueryExpansionEnum(String code) {
        this.code = code;
    }

    /**
     * @return 线上契约字符串
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 解析请求或配置中的扩展策略。无法识别（含 {@code memory_rewrite}）时为 {@link #NONE}。
     *
     * @param raw 原始字符串
     * @return 策略
     */
    public static ProductionQueryExpansionEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase().replace('-', '_');
        for (ProductionQueryExpansionEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return NONE;
    }
}
