package com.feike.ai.production.rag.ingest.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 异步入库任务状态。
 */
public enum IngestJobStatusEnum {

    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String code;

    IngestJobStatusEnum(String code) {
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
     * @param raw 原始值
     * @return 状态；无法识别时为 {@link #FAILED}
     */
    public static IngestJobStatusEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAILED;
        }
        String key = raw.trim().toLowerCase();
        for (IngestJobStatusEnum value : values()) {
            if (value.code.equals(key) || value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return FAILED;
    }

    /**
     * @return 是否已结束
     */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
