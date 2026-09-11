package com.feike.ai.production.audit.service;

import com.feike.ai.production.audit.model.AuditEntryDO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 审计落库。失败只打日志，绝不改变业务响应。
 */
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 数据源
     */
    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条审计。在虚拟线程里执行，失败不影响调用方。
     *
     * @param tenantId       租户
     * @param principal      用户
     * @param action         动作
     * @param path           路径
     * @param status         状态码
     * @param runId          run
     * @param questionSha256 问题摘要
     * @param durationMs     耗时
     * @param ip             IP
     */
    public void record(
        String tenantId,
        String principal,
        String action,
        String path,
        int status,
        String runId,
        String questionSha256,
        Integer durationMs,
        String ip
    ) {
        record(tenantId, principal, action, path, status, runId, questionSha256, durationMs, ip, null, null);
    }

    /**
     * 写入一条审计，可带工具名。失败不影响调用方。
     *
     * @param tenantId       租户
     * @param principal      用户
     * @param action         动作
     * @param path           路径
     * @param status         状态码
     * @param runId          run
     * @param questionSha256 问题或参数摘要
     * @param durationMs     耗时
     * @param ip             IP
     * @param toolName       工具名
     * @param denied         是否被拒绝
     */
    public void record(
        String tenantId,
        String principal,
        String action,
        String path,
        int status,
        String runId,
        String questionSha256,
        Integer durationMs,
        String ip,
        String toolName,
        Boolean denied
    ) {
        Thread.ofVirtual().name("prod-audit").start(() -> {
        try {
            jdbc.update(
                """
                    INSERT INTO prod_audit_log
                      (tenant_id, principal, action, path, status, run_id, question_sha256, duration_ms, ip,
                       tool_name, denied)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                tenantId,
                principal,
                action,
                path,
                status,
                runId,
                questionSha256,
                durationMs,
                ip,
                toolName,
                denied
            );
        } catch (RuntimeException ex) {
            log.warn("审计写入失败: {}", ex.toString());
        }
        });
    }

    /**
     * 本租户最近记录。
     *
     * @param tenantId 租户
     * @param limit    条数
     * @return 新到旧
     */
    public List<AuditEntryDO> recent(String tenantId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 200);
        return jdbc.query(
            """
                SELECT id, tenant_id, principal, action, path, status, run_id, question_sha256,
                       duration_ms, ip, created_at, tool_name, denied
                  FROM prod_audit_log
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """,
            (rs, rowNum) -> {
                Timestamp created = rs.getTimestamp("created_at");
                Boolean denied = (Boolean) rs.getObject("denied");
                return new AuditEntryDO(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getString("principal"),
                    rs.getString("action"),
                    rs.getString("path"),
                    rs.getInt("status"),
                    rs.getString("run_id"),
                    rs.getString("question_sha256"),
                    (Integer) rs.getObject("duration_ms"),
                    rs.getString("ip"),
                    created == null ? Instant.EPOCH : created.toInstant(),
                    rs.getString("tool_name"),
                    denied
                );
            },
            tenantId,
            cap
        );
    }
}
