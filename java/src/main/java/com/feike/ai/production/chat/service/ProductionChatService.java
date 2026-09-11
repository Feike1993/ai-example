package com.feike.ai.production.chat.service;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.chat.model.ChatImage;
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
     * 流式问答，可附带一张图。有图时落库问句加 {@code [图片]} 前缀，不写二进制。
     *
     * @param writer         事件出口
     * @param principal      当前用户
     * @param sessionId      会话
     * @param question       问题
     * @param provider       模型；有图且为空时用视觉默认 Provider
     * @param topK           topK
     * @param queryExpansion none / rewrite / hyde
     * @param imageBytes     图片；可空
     * @param imageMime      图片 mime
     */
    void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion,
        byte[] imageBytes,
        String imageMime
    );

    /**
     * 流式问答，可附带多张图。落库只写 {@code [图片]} / {@code [图片×N]} 占位。
     *
     * @param writer         事件出口
     * @param principal      当前用户
     * @param sessionId      会话
     * @param question       问题
     * @param provider       模型；有图且为空时用视觉默认 Provider
     * @param topK           topK
     * @param queryExpansion none / rewrite / hyde
     * @param images         已校验的附件；空则纯文本
     */
    void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion,
        List<ChatImage> images
    );

    /**
     * 流式问答，可附带多张图与本轮文档摘录。落库只写图片 / 文档占位。
     *
     * @param writer         事件出口
     * @param principal      当前用户
     * @param sessionId      会话
     * @param question       问题
     * @param provider       模型
     * @param topK           topK
     * @param queryExpansion none / rewrite / hyde
     * @param images         已校验的识图附件；空则无图
     * @param documents      已抽取（含可选 OCR）的文档；空则无文档
     * @param ocrUsed        本轮是否调用过 VL 转写
     */
    void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion,
        List<ChatImage> images,
        List<ChatDocument> documents,
        boolean ocrUsed
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
