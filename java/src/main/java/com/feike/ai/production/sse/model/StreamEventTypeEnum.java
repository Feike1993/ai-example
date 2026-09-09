package com.feike.ai.production.sse.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 生产链路 SSE 的事件名全集。
 * <p>
 * 教学样例三个流各发各的（chat 裸 token、rag 发 sources、agent 才有 done），
 * 客户端只能靠「收到过数据就当成功」来收尾，断流会被误判成正常完成。
 * 这里把事件名收敛成一套闭合契约：非终态事件可以出现任意多次，
 * 终态事件（{@link #DONE} / {@link #ERROR}）恰好出现一次且必须是最后一条。
 */
public enum StreamEventTypeEnum {

    /** 流开始，携带 runId 与本次请求的回显参数；永远是 seq=0 的第一条。 */
    META("meta"),

    /** RAG 检索命中列表，出现在任何 delta 之前。 */
    SOURCES("sources"),

    /** 答案增量 token。 */
    DELTA("delta"),

    /** Agent 单步状态（工具调用与结果）。 */
    STEP("step"),

    /** Token 用量统计。 */
    USAGE("usage"),

    /** 终态：成功完成。客户端只有收到它才能判定成功。 */
    DONE("done"),

    /** 终态：失败，携带 code 与 message。 */
    ERROR("error");

    private final String code;

    StreamEventTypeEnum(String code) {
        this.code = code;
    }

    /**
     * @return SSE 事件名（线上小写契约）
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * @return 是否为终态事件；终态之后不允许再写任何事件
     */
    public boolean terminal() {
        return this == DONE || this == ERROR;
    }

    /**
     * 解析日志 / Redis 中的事件名；同时接受大写枚举名与线上小写 code。
     *
     * @param raw 存储值
     * @return 事件类型
     */
    public static StreamEventTypeEnum fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty event type");
        }
        String key = raw.trim();
        for (StreamEventTypeEnum value : values()) {
            if (value.code.equals(key) || value.name().equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown event type: " + raw);
    }
}
