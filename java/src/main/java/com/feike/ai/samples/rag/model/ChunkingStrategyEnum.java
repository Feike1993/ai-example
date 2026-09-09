package com.feike.ai.samples.rag.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 分块策略：固定 token、结构语义、或父子文档（7b）。
 */
public enum ChunkingStrategyEnum {
    TOKEN("token"),
    SEMANTIC("semantic"),
    PARENT_CHILD("parent_child");

    private final String code;

    ChunkingStrategyEnum(String code) {
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
     * 解析请求中的分块策略；空或未知时默认 token。
     *
     * @param raw 请求字符串
     * @return 分块策略
     */
    public static ChunkingStrategyEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return TOKEN;
        }
        String key = raw.trim().toLowerCase().replace('-', '_');
        for (ChunkingStrategyEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return TOKEN;
    }
}
