package com.feike.ai.samples.context;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 持久会话；与 RAG 同库。配置 {@code app.ai.context.store=jdbc}（默认）时启用。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.context", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcChatSessionStore implements ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatSessionStore.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcChatSessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等建表。
     */
    @PostConstruct
    public void ensureSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chat_session_message (
                session_id VARCHAR(128) NOT NULL,
                seq INTEGER NOT NULL,
                role VARCHAR(32) NOT NULL,
                content TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (session_id, seq)
            )
            """);
        log.info("chat_session_message 表已就绪");
    }

    @Override
    public List<Message> snapshot(String sessionId) {
        List<Message> messages = jdbcTemplate.query(
            """
                SELECT role, content FROM chat_session_message
                WHERE session_id = ? ORDER BY seq ASC
                """,
            (rs, rowNum) -> ChatSessionStore.fromRole(rs.getString("role"), rs.getString("content")),
            sessionId
        );
        return new ArrayList<>(messages);
    }

    @Override
    public void replace(String sessionId, List<Message> messages) {
        jdbcTemplate.update("DELETE FROM chat_session_message WHERE session_id = ?", sessionId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int seq = 0;
        for (Message message : messages) {
            jdbcTemplate.update(
                """
                    INSERT INTO chat_session_message (session_id, seq, role, content)
                    VALUES (?, ?, ?, ?)
                    """,
                sessionId,
                seq++,
                ChatSessionStore.roleOf(message),
                ChatSessionStore.textOf(message)
            );
        }
    }

    @Override
    public synchronized void appendTurn(String sessionId, String user, String assistant) {
        Integer maxSeq = jdbcTemplate.query(
            "SELECT MAX(seq) FROM chat_session_message WHERE session_id = ?",
            rs -> rs.next() ? rs.getObject(1, Integer.class) : null,
            sessionId
        );
        int next = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
            """
                INSERT INTO chat_session_message (session_id, seq, role, content)
                VALUES (?, ?, ?, ?)
                """,
            sessionId, next, "user", user == null ? "" : user
        );
        jdbcTemplate.update(
            """
                INSERT INTO chat_session_message (session_id, seq, role, content)
                VALUES (?, ?, ?, ?)
                """,
            sessionId, next + 1, "assistant", assistant == null ? "" : assistant
        );
    }

    @Override
    public boolean clear(String sessionId) {
        int deleted = jdbcTemplate.update("DELETE FROM chat_session_message WHERE session_id = ?", sessionId);
        return deleted > 0;
    }

    @Override
    public String storeKind() {
        return "jdbc";
    }
}
