package com.feike.ai.production.session.dao.impl;

import com.feike.ai.production.session.dao.ProductionChatSessionDAO;
import com.feike.ai.production.session.model.SessionMessageDO;
import com.feike.ai.production.session.service.SessionNotOwnedException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 内存替身：让编排层的会话行为可以脱离数据库单测。
 * <p>
 * 只实现语义（追加、幂等、读回、租户隔离），不模拟事务和并发。
 */
public class FakeProductionChatSessionDAOImpl implements ProductionChatSessionDAO {

    private final Map<String, String> owners = new LinkedHashMap<>();
    private final Map<String, List<SessionMessageDO>> data = new LinkedHashMap<>();

    /** 非空时 {@link #appendTurn} 直接抛出，用于验证落库失败的降级路径。 */
    public RuntimeException appendFailure;

    @Override
    public List<SessionMessageDO> history(String tenantId, String sessionId) {
        if (!ownedBy(tenantId, sessionId)) {
            return List.of();
        }
        return List.copyOf(data.getOrDefault(sessionId, List.of()));
    }

    @Override
    public List<Message> historyMessages(String tenantId, String sessionId) {
        List<Message> messages = new ArrayList<>();
        for (SessionMessageDO row : history(tenantId, sessionId)) {
            messages.add("assistant".equals(row.role())
                ? new AssistantMessage(row.content())
                : new UserMessage(row.content()));
        }
        return messages;
    }

    @Override
    public void appendTurn(
        String tenantId,
        String sessionId,
        UUID turnId,
        String runId,
        String user,
        String assistant
    ) {
        if (appendFailure != null) {
            throw appendFailure;
        }
        String owner = owners.get(sessionId);
        if (owner != null && !owner.equals(tenantId)) {
            throw new SessionNotOwnedException(sessionId);
        }
        owners.putIfAbsent(sessionId, tenantId);
        List<SessionMessageDO> rows = data.computeIfAbsent(sessionId, key -> new ArrayList<>());
        if (rows.stream().anyMatch(row -> row.turnId().equals(turnId.toString()))) {
            return;
        }
        int seq = rows.size();
        rows.add(new SessionMessageDO(seq, turnId.toString(), "user", user, runId, Instant.now()));
        rows.add(new SessionMessageDO(seq + 1, turnId.toString(), "assistant", assistant, runId, Instant.now()));
    }

    @Override
    public boolean clear(String tenantId, String sessionId) {
        if (!ownedBy(tenantId, sessionId)) {
            return false;
        }
        owners.remove(sessionId);
        return data.remove(sessionId) != null;
    }

    @Override
    public boolean ownedBy(String tenantId, String sessionId) {
        return Objects.equals(owners.get(sessionId), tenantId);
    }
}
