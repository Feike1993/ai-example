package com.feike.ai.production.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL 实现，把教学版 {@code JdbcChatSessionStore} 的三个并发缺陷逐个补上。
 *
 * <h2>一、整轮原子</h2>
 * user 与 assistant 在同一个事务里写。教学版分两次裸写，中间崩掉就会在历史里留下
 * 一条没有回答的孤儿 user 消息，而这种脏数据会一直参与后续每一轮的 prompt。
 *
 * <h2>二、序号分配</h2>
 * seq 用 {@code INSERT ... SELECT COALESCE(MAX(seq), -1) + 1} 在单条语句里算完，
 * 消掉「先 SELECT MAX 再 INSERT」之间的 TOCTOU 窗口。这还不够——两个并发事务
 * 仍可能读到同一个 MAX，此时复合主键 {@code (session_id, seq)} 让后提交者失败，
 * 由本类重取一次号。所以正确性落在数据库约束上，不依赖任何应用层的锁。
 *
 * <h2>三、为什么用 TransactionTemplate 而不是 {@code @Transactional}</h2>
 * 重试必须发生在事务<b>外面</b>：主键冲突会把当前事务标记为 rollback-only，
 * 在里面重试只会连环失败。而 {@code @Transactional} 方法内部自调用不走代理，
 * 拆成两个 Bean 又只是为了绕过代理机制而增加一层。用模板把「一次尝试 = 一个事务」
 * 写成显式的循环体，边界在代码里直接看得见。
 *
 * <h2>幂等</h2>
 * 每次尝试先按 {@code turnId} 查重。它同时覆盖两种重复：本类自己的重试重放，
 * 以及客户端重复提交同一轮。数据库侧还有 {@code uk_prod_chat_message_turn_role}
 * 唯一索引兜底，防止查重与插入之间的竞态真的写进两条。
 */
public class JdbcProductionChatSessionStore implements ProductionChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductionChatSessionStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxAttempts;

    /**
     * @param jdbc        数据源模板
     * @param tx          事务模板，一次尝试一个事务
     * @param maxAttempts seq 冲突后的最大尝试次数（含首次）
     */
    public JdbcProductionChatSessionStore(JdbcTemplate jdbc, TransactionTemplate tx, int maxAttempts) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public List<SessionMessage> history(String sessionId) {
        return jdbc.query(
            """
                SELECT seq, turn_id, role, content, run_id, created_at
                  FROM prod_chat_message
                 WHERE session_id = ?
                 ORDER BY seq ASC
                """,
            (rs, rowNum) -> {
                Timestamp createdAt = rs.getTimestamp("created_at");
                return new SessionMessage(
                    rs.getInt("seq"),
                    rs.getString("turn_id"),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("run_id"),
                    createdAt == null ? null : createdAt.toInstant()
                );
            },
            sessionId
        );
    }

    @Override
    public List<Message> historyMessages(String sessionId) {
        List<Message> messages = new ArrayList<>();
        for (SessionMessage row : history(sessionId)) {
            messages.add(toMessage(row.role(), row.content()));
        }
        return messages;
    }

    @Override
    public void appendTurn(String sessionId, UUID turnId, String runId, String user, String assistant) {
        String userText = user == null ? "" : user;
        String assistantText = assistant == null ? "" : assistant;

        DuplicateKeyException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                tx.executeWithoutResult(status -> insertTurn(sessionId, turnId, runId, userText, assistantText));
                return;
            } catch (DuplicateKeyException ex) {
                // 并发下同一 session 抢到了同一个 seq。下一轮尝试会重新取号；
                // 若冲突其实来自 turn_id（重复提交），下一轮的查重会直接短路返回。
                last = ex;
                log.debug("session={} turn={} 第 {} 次取号冲突，重试", sessionId, turnId, attempt);
            }
        }
        throw new SessionWriteConflictException(sessionId, maxAttempts, last);
    }

    @Override
    public boolean clear(String sessionId) {
        // 消息由外键 ON DELETE CASCADE 一并删掉，不必手动清两张表
        return jdbc.update("DELETE FROM prod_chat_session WHERE session_id = ?", sessionId) > 0;
    }

    /** 单次尝试的事务体：确保会话行存在 → 查重 → 依次插 user 与 assistant。 */
    private void insertTurn(String sessionId, UUID turnId, String runId, String user, String assistant) {
        jdbc.update(
            """
                INSERT INTO prod_chat_session (session_id) VALUES (?)
                ON CONFLICT (session_id) DO UPDATE SET updated_at = NOW()
                """,
            sessionId
        );

        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM prod_chat_message WHERE turn_id = CAST(? AS uuid)",
            Integer.class,
            turnId.toString()
        );
        if (existing != null && existing > 0) {
            log.debug("session={} turn={} 已落库，跳过", sessionId, turnId);
            return;
        }

        appendMessage(sessionId, turnId, runId, "user", user);
        // assistant 的 seq 自然是 user + 1：上一条已在本事务内可见，MAX(seq) 已包含它
        appendMessage(sessionId, turnId, runId, "assistant", assistant);
    }

    private void appendMessage(String sessionId, UUID turnId, String runId, String role, String content) {
        jdbc.update(
            """
                INSERT INTO prod_chat_message (session_id, seq, turn_id, role, content, run_id)
                SELECT ?, COALESCE(MAX(seq), -1) + 1, CAST(? AS uuid), ?, ?, ?
                  FROM prod_chat_message
                 WHERE session_id = ?
                """,
            sessionId, turnId.toString(), role, content, runId, sessionId
        );
    }

    private static Message toMessage(String role, String content) {
        String text = content == null ? "" : content;
        if (role == null) {
            return new UserMessage(text);
        }
        return switch (role.toLowerCase()) {
            case "assistant" -> new AssistantMessage(text);
            case "system" -> new SystemMessage(text);
            default -> new UserMessage(text);
        };
    }
}
