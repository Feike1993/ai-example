package com.feike.ai.production.session.dao;

import com.feike.ai.production.session.model.SessionMessageDO;
import com.feike.ai.samples.context.dao.ChatSessionDAO;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.UUID;

/**
 * 工业级会话存储。
 * <p>
 * 与教学版 {@code com.feike.ai.samples.context.ChatSessionDAO} 的差别集中在写入语义：
 * 一轮对话是一个原子单位（要么 user 和 assistant 都在，要么都不在），
 * 序号分配对并发安全，重复提交同一轮不会写重。读取侧反而更简单——
 * 这里不提供 {@code replace()}，生产会话只追加不覆写。
 */
public interface ProductionChatSessionDAO {

    /**
     * 解析或新建 sessionId。
     *
     * @param sessionId 客户端带来的 id，可为空
     * @return 非空 id；传空时生成新的
     */
    default String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    /**
     * 按 seq 升序读出全部历史。
     *
     * @param tenantId  租户；不属于该租户时返回空列表（不暴露存在性）
     * @param sessionId 会话 id
     * @return 消息列表
     */
    List<SessionMessageDO> history(String tenantId, String sessionId);

    /**
     * 读出历史并转成 Spring AI 消息，供直接拼进 prompt。
     *
     * @param tenantId  租户
     * @param sessionId 会话 id
     * @return 可变列表
     */
    List<Message> historyMessages(String tenantId, String sessionId);

    /**
     * 原子追加一轮对话。
     * <p>
     * 实现必须保证：user 与 assistant 同事务落库、seq 在并发下不重不跳、
     * 同一 {@code turnId} 重复调用不会产生重复消息。
     *
     * @param tenantId  租户；会话必须属于该租户
     * @param sessionId 会话 id
     * @param turnId    轮次 id，同时作为幂等键
     * @param runId     产生本轮的 SSE run，可为 {@code null}
     * @param user      用户文本
     * @param assistant 助手文本
     */
    void appendTurn(String tenantId, String sessionId, UUID turnId, String runId, String user, String assistant);

    /**
     * 清空会话及其全部消息。
     *
     * @param tenantId  租户
     * @param sessionId 会话 id
     * @return 会话原先是否存在且属于该租户
     */
    boolean clear(String tenantId, String sessionId);

    /**
     * @param tenantId  租户
     * @param sessionId 会话 id
     * @return 该租户是否拥有此会话
     */
    boolean ownedBy(String tenantId, String sessionId);
}
