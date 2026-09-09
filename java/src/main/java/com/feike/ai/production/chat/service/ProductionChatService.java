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
}
