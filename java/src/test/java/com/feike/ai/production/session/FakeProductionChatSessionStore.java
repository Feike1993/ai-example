package com.feike.ai.production.session;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存替身：让编排层的会话行为可以脱离数据库单测。
 * <p>
 * 只实现语义（追加、幂等、读回），不模拟事务和并发——那两件事在
 * {@code ProductionChatSessionStoreIT} 里用真实 PostgreSQL 验证，
 * 在替身里模拟出来的「并发安全」没有任何证明力。
 */
public class FakeProductionChatSessionStore implements ProductionChatSessionStore {

    private final Map<String, List<SessionMessage>> data = new LinkedHashMap<>();

    /** 非空时 {@link #appendTurn} 直接抛出，用于验证落库失败的降级路径。 */
    public RuntimeException appendFailure;

    @Override
    public List<SessionMessage> history(String sessionId) {
        return List.copyOf(data.getOrDefault(sessionId, List.of()));
    }

    @Override
    public List<Message> historyMessages(String sessionId) {
        List<Message> messages = new ArrayList<>();
        for (SessionMessage row : history(sessionId)) {
            messages.add("assistant".equals(row.role())
                ? new AssistantMessage(row.content())
                : new UserMessage(row.content()));
        }
        return messages;
    }

    @Override
    public void appendTurn(String sessionId, UUID turnId, String runId, String user, String assistant) {
        if (appendFailure != null) {
            throw appendFailure;
        }
        List<SessionMessage> rows = data.computeIfAbsent(sessionId, key -> new ArrayList<>());
        if (rows.stream().anyMatch(row -> row.turnId().equals(turnId.toString()))) {
            return;
        }
        int seq = rows.size();
        rows.add(new SessionMessage(seq, turnId.toString(), "user", user, runId, Instant.now()));
        rows.add(new SessionMessage(seq + 1, turnId.toString(), "assistant", assistant, runId, Instant.now()));
    }

    @Override
    public boolean clear(String sessionId) {
        return data.remove(sessionId) != null;
    }
}
