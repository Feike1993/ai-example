package com.feike.ai.production.chat.service;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.rag.model.ProductionSource;
import com.feike.ai.production.session.model.SessionMessageDO;
import com.feike.ai.production.sse.service.SseStreamWriter;
import java.util.List;

/** ProductionChatService 业务门面。 */
public interface ProductionChatService {

    String SESSION_BUSY = "session_busy";
    record ChatAnswer(
        String sessionId,
        String answer,
        List<ProductionSource> sources,
        boolean retrievalEmpty,
        String retrievalMode,
        int historyMessages,
        boolean persisted,
        String queryExpansion
    ) {
        /**
         * 无扩展字段的兼容构造。
         *
         * @param sessionId       会话
         * @param answer          答案
         * @param sources         来源
         * @param retrievalEmpty  空检索
         * @param retrievalMode   检索模式
         * @param historyMessages 历史条数
         * @param persisted       是否落库
         */
        public ChatAnswer(
            String sessionId,
            String answer,
            List<ProductionSource> sources,
            boolean retrievalEmpty,
            String retrievalMode,
            int historyMessages,
            boolean persisted
        ) {
            this(sessionId, answer, sources, retrievalEmpty, retrievalMode, historyMessages, persisted, "none");
        }
    }
    boolean sessionEnabled();
    List<SessionMessageDO> history(ProductionPrincipal principal, String sessionId);
    boolean clearSession(ProductionPrincipal principal, String sessionId);
    ChatAnswer answer(ProductionPrincipal principal, String sessionId, String question, String provider, Integer topK);

    /**
     * 同步问答。
     *
     * @param principal      当前用户
     * @param sessionId      会话
     * @param question       问题
     * @param provider       模型
     * @param topK           topK
     * @param queryExpansion none / rewrite / hyde；空则用配置默认
     * @return 答案
     */
    ChatAnswer answer(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion
    );

    /**
     * 流式问答。
     *
     * @param writer    事件出口
     * @param principal 当前用户
     * @param sessionId 会话
     * @param question  问题
     * @param provider  模型
     * @param topK      topK
     */
    void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK
    );

    /**
     * 流式问答。
     *
     * @param writer         事件出口
     * @param principal      当前用户
     * @param sessionId      会话
     * @param question       问题
     * @param provider       模型
     * @param topK           topK
     * @param queryExpansion none / rewrite / hyde；空则用配置默认
     */
    void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion
    );

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
