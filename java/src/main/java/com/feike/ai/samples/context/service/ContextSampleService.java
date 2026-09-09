package com.feike.ai.samples.context.service;

import com.feike.ai.core.model.TokenUsageDTO;
import com.feike.ai.samples.context.dao.ChatSessionDAO;
import com.feike.ai.samples.context.model.ContextStrategyEnum;
import java.util.List;

/** ContextSampleService 业务门面。 */
public interface ContextSampleService {

    record ContextChatResult(String sessionId, String strategy, String content, int rawMessageCount, int sentMessageCount, int approxTokens, int droppedCount, String summary, TokenUsageDTO usage, String store) {}
    ContextChatResult chat(String sessionId, String prompt, String provider, ContextStrategyEnum strategy);
    List<ChatSessionDAO.MessageVO> session(String sessionId);
    boolean clear(String sessionId);
    String storeKind();
}
