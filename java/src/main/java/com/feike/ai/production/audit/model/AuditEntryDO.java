package com.feike.ai.production.audit.model;

import java.time.Instant;

/**
 * 一条审计记录。不包含问题原文，只有 sha256。
 *
 * @param id             主键
 * @param tenantId       租户
 * @param principal      用户名
 * @param action         动作名
 * @param path           请求路径
 * @param status         HTTP 状态
 * @param runId          SSE run，可空
 * @param questionSha256 问题摘要，可空
 * @param durationMs     耗时
 * @param ip             客户端 IP
 * @param createdAt      时间
 * @param toolName       工具名，可空
 * @param denied         是否被策略拒绝，可空
 */
public record AuditEntryDO(
    long id,
    String tenantId,
    String principal,
    String action,
    String path,
    int status,
    String runId,
    String questionSha256,
    Integer durationMs,
    String ip,
    Instant createdAt,
    String toolName,
    Boolean denied
) {}
