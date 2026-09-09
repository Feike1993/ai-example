package com.feike.ai.samples.rag.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 引用模式：自由文本或强制结构化 citation。
 */
public enum CitationModeEnum {
    NONE("none"),
    REQUIRED("required");

    private final String code;

    CitationModeEnum(String code) {
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
     * 解析请求中的引用模式；空或未知时默认 none。
     *
     * @param raw 请求字符串
     * @return 引用模式
     */
    public static CitationModeEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase();
        for (CitationModeEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return NONE;
    }
}
