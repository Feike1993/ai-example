package com.feike.ai.samples.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话存储；重启清空。配置 {@code app.ai.context.store=memory} 时启用。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.context", name = "store", havingValue = "memory")
public class InMemoryChatSessionStore implements ChatSessionStore {

    private final ConcurrentHashMap<String, List<Message>> sessions = new ConcurrentHashMap<>();

    @Override
    public List<Message> snapshot(String sessionId) {
        List<Message> raw = sessions.get(sessionId);
        if (raw == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(raw);
    }

    @Override
    public void replace(String sessionId, List<Message> messages) {
        sessions.put(sessionId, new ArrayList<>(messages));
    }

    @Override
    public synchronized void appendTurn(String sessionId, String user, String assistant) {
        List<Message> list = sessions.computeIfAbsent(sessionId, id -> new ArrayList<>());
        list.add(new UserMessage(user));
        list.add(new AssistantMessage(assistant == null ? "" : assistant));
    }

    @Override
    public boolean clear(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public String storeKind() {
        return "memory";
    }
}
