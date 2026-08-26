package com.feike.ai.samples.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话存储；重启清空。生产请换 Redis/DB，见 backlog。
 */
@Component
public class InMemoryChatSessionStore {

    private final ConcurrentHashMap<String, List<Message>> sessions = new ConcurrentHashMap<>();

    /**
     * 解析或新建 sessionId。
     *
     * @param sessionId 可空
     * @return 非空 id
     */
    public String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /**
     * 返回会话消息的可变副本（调用方可改副本，不影响存储）。
     *
     * @param sessionId 会话 id
     * @return 消息列表副本
     */
    public List<Message> snapshot(String sessionId) {
        List<Message> raw = sessions.get(sessionId);
        if (raw == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(raw);
    }

    /**
     * 用完整列表覆盖会话（应在同步块外先算好）。
     *
     * @param sessionId 会话 id
     * @param messages  完整历史
     */
    public void replace(String sessionId, List<Message> messages) {
        sessions.put(sessionId, new ArrayList<>(messages));
    }

    /**
     * 追加一轮 user + assistant。
     *
     * @param sessionId 会话 id
     * @param user      用户文本
     * @param assistant 助手文本
     */
    public synchronized void appendTurn(String sessionId, String user, String assistant) {
        List<Message> list = sessions.computeIfAbsent(sessionId, id -> new ArrayList<>());
        list.add(new UserMessage(user));
        list.add(new AssistantMessage(assistant == null ? "" : assistant));
    }

    /**
     * 清空会话。
     *
     * @param sessionId 会话 id
     * @return 是否原先存在
     */
    public boolean clear(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /**
     * 导出便于 JSON 展示的视图。
     *
     * @param sessionId 会话 id
     * @return role + content 列表
     */
    public List<MessageView> views(String sessionId) {
        List<MessageView> views = new ArrayList<>();
        for (Message message : snapshot(sessionId)) {
            views.add(new MessageView(roleOf(message), textOf(message)));
        }
        return views;
    }

    /**
     * @param role    user / assistant / system / other
     * @param content 文本
     */
    public record MessageView(String role, String content) {}

    static String textOf(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    static String roleOf(Message message) {
        MessageType type = message.getMessageType();
        if (type == null) {
            return "other";
        }
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> type.name().toLowerCase();
        };
    }

    /**
     * 是否用户或助手消息。
      * @param message   消息
      * @return 是否用户或助手消息
     */
    static boolean isUserOrAssistant(Message message) {
        MessageType type = message.getMessageType();
        return type == MessageType.USER || type == MessageType.ASSISTANT;
    }

    static SystemMessage asSystem(String text) {
        return new SystemMessage(text);
    }
}
