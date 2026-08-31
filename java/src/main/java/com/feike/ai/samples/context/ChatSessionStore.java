package com.feike.ai.samples.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话消息存储抽象：内存或 JDBC 实现可互换，trim / summarize 策略不依赖具体存储。
 */
public interface ChatSessionStore {

    /**
     * 解析或新建 sessionId。
     *
     * @param sessionId 可空
     * @return 非空 id
     */
    default String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /**
     * 返回会话消息的可变副本。
     *
     * @param sessionId 会话 id
     * @return 消息列表副本
     */
    List<Message> snapshot(String sessionId);

    /**
     * 用完整列表覆盖会话。
     *
     * @param sessionId 会话 id
     * @param messages  完整历史
     */
    void replace(String sessionId, List<Message> messages);

    /**
     * 追加一轮 user + assistant。
     *
     * @param sessionId 会话 id
     * @param user      用户文本
     * @param assistant 助手文本
     */
    void appendTurn(String sessionId, String user, String assistant);

    /**
     * 清空会话。
     *
     * @param sessionId 会话 id
     * @return 是否原先存在消息
     */
    boolean clear(String sessionId);

    /**
     * 导出便于 JSON 展示的视图。
     *
     * @param sessionId 会话 id
     * @return role + content 列表
     */
    default List<MessageView> views(String sessionId) {
        List<MessageView> views = new ArrayList<>();
        for (Message message : snapshot(sessionId)) {
            views.add(new MessageView(roleOf(message), textOf(message)));
        }
        return views;
    }

    /**
     * 存储实现标识，写入 API 响应对照。
     *
     * @return {@code memory} 或 {@code jdbc}
     */
    String storeKind();

    /**
     * @param role    user / assistant / system / other
     * @param content 文本
     */
    record MessageView(String role, String content) {}

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

    static boolean isUserOrAssistant(Message message) {
        MessageType type = message.getMessageType();
        return type == MessageType.USER || type == MessageType.ASSISTANT;
    }

    static SystemMessage asSystem(String text) {
        return new SystemMessage(text);
    }

    static Message fromRole(String role, String content) {
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
