package com.feike.ai.samples.rag.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 检索模式：纯向量或 Hybrid RRF。
 */
public enum RetrievalModeEnum {
    /** 仅 pgvector 相似度 */
    VECTOR("vector"),
    /** 向量 + PG 全文 + RRF */
    HYBRID("hybrid");

    private final String code;

    RetrievalModeEnum(String code) {
        this.code = code;
    }

    /**
     * @return 线上契约字符串（小写）
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 解析请求中的检索模式；空或未知时默认向量。
     *
     * @param raw 请求字符串
     * @return 检索模式
     */
    public static RetrievalModeEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return VECTOR;
        }
        String key = raw.trim().toLowerCase();
        for (RetrievalModeEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return VECTOR;
    }
}
