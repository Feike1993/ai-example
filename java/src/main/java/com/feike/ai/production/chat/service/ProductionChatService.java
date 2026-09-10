package com.feike.ai.production.chat.service;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.rag.model.ProductionSource;
import com.feike.ai.production.session.model.SessionMessageDO;
import com.feike.ai.production.sse.service.SseStreamWriter;
import java.util.List;

/** ProductionChatService 业务门面。 */
public interface ProductionChatService {

    String SESSION_BUSY = "session_busy";
    record ChatAnswer(String sessionId, String answer, List<ProductionSource> sources, boolean retrievalEmpty, String retrievalMode, int historyMessages, boolean persisted) {}
    boolean sessionEnabled();
    List<SessionMessageDO> history(ProductionPrincipal principal, String sessionId);
    boolean clearSession(ProductionPrincipal principal, String sessionId);
    ChatAnswer answer(ProductionPrincipal principal, String sessionId, String question, String provider, Integer topK);
    void streamAnswer(SseStreamWriter writer, ProductionPrincipal principal, String sessionId, String question, String provider, Integer topK);

    /**
     * 占用会话锁一段时间后释放。给多实例演示用，不调 LLM。
     *
     * @param principal 当前用户
     * @param sessionId 会话；空则新建
     * @param holdMs    持锁毫秒，上限 10s
     * @return 实际 sessionId 与持锁时长
     */
    LockHold probeLock(ProductionPrincipal principal, String sessionId, Integer holdMs);

    /**
     * @param sessionId 会话
     * @param heldMs    实际持锁毫秒
     */
    record LockHold(String sessionId, int heldMs) {}
}
