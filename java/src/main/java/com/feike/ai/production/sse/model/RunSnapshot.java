package com.feike.ai.production.sse.model;

/**
 * run 的状态快照。
 *
 * @param runId   run 唯一标识
 * @param state   当前状态
 * @param lastSeq 已写出的最大序号；无事件时为 -1
 */
public record RunSnapshot(String runId, RunStateEnum state, long lastSeq) {
}
