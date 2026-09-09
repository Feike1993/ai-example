package com.feike.ai.samples.rag.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 查询扩展：无 / 改写短句 / HyDE 假想文档 / 记忆辅助改写。
 */
public enum QueryExpansionEnum {
    NONE("none"),
    REWRITE("rewrite"),
    HYDE("hyde"),
    /** 先 recall 记忆再改写查询（第十二期）；记忆不进 RAG sources */
    MEMORY_REWRITE("memory_rewrite");

    private final String code;

    QueryExpansionEnum(String code) {
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
     * 解析请求中的扩展策略。
     *
     * @param raw 请求字符串
     * @return 扩展策略；无法识别时为 {@link #NONE}
     */
    public static QueryExpansionEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase().replace('-', '_');
        if ("memoryrewrite".equals(key)) {
            return MEMORY_REWRITE;
        }
        for (QueryExpansionEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return NONE;
    }
}
