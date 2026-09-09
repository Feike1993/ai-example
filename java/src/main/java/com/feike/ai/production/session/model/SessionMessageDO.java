package com.feike.ai.production.session.model;

import java.time.Instant;

/**
 * 一条已落库的会话消息。
 *
 * @param seq       会话内序号，从 0 开始连续递增
 * @param turnId    所属轮次；同一轮的 user 与 assistant 共享
 * @param role      {@code user} / {@code assistant} / {@code system}
 * @param content   正文
 * @param runId     产生该消息的 SSE run；历史导入等场景可为 {@code null}
 * @param createdAt 落库时间
 */
public record SessionMessageDO(
    int seq,
    String turnId,
    String role,
    String content,
    String runId,
    Instant createdAt
) {}
