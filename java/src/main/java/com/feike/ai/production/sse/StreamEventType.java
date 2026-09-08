package com.feike.ai.production.sse;

/**
 * 生产链路 SSE 的事件名全集。
 * <p>
 * 教学样例三个流各发各的（chat 裸 token、rag 发 sources、agent 才有 done），
 * 客户端只能靠「收到过数据就当成功」来收尾，断流会被误判成正常完成。
 * 这里把事件名收敛成一套闭合契约：非终态事件可以出现任意多次，
 * 终态事件（{@link #done} / {@link #error}）恰好出现一次且必须是最后一条。
 */
public enum StreamEventType {

    /** 流开始，携带 runId 与本次请求的回显参数；永远是 seq=0 的第一条。 */
    meta,

    /** RAG 检索命中列表，出现在任何 delta 之前。 */
    sources,

    /** 答案增量 token。 */
    delta,

    /** Agent 单步状态（工具调用与结果）。 */
    step,

    /** Token 用量统计。 */
    usage,

    /** 终态：成功完成。客户端只有收到它才能判定成功。 */
    done,

    /** 终态：失败，携带 code 与 message。 */
    error;

    /**
     * @return 是否为终态事件；终态之后不允许再写任何事件
     */
    public boolean terminal() {
        return this == done || this == error;
    }
}
