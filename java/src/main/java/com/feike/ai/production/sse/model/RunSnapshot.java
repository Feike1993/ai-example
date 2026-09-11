package com.feike.ai.production.sse.model;

/**
 * run 的状态快照。
 *
 * @param runId    run 唯一标识
 * @param state    当前状态
 * @param lastSeq  已写出的最大序号；无事件时为 -1
 * @param tenantId 创建该 run 的租户；旧数据或测试未写入时为空
 */
public record RunSnapshot(String runId, RunStateEnum state, long lastSeq, String tenantId) {

    /**
     * 无租户的快照，给未绑租户的旧日志与单测用。
     *
     * @param runId   run
     * @param state   状态
     * @param lastSeq 最大序号
     */
    public RunSnapshot(String runId, RunStateEnum state, long lastSeq) {
        this(runId, state, lastSeq, null);
    }
}
