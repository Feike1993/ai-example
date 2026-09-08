package com.feike.ai.production.chat;

import com.feike.ai.core.context.ContextBudget;
import com.feike.ai.production.ProductionProperties;
import com.feike.ai.production.lock.SessionLock;
import com.feike.ai.production.rag.ProductionSource;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.session.ProductionChatSessionStore;
import com.feike.ai.production.session.SessionMessage;
import com.feike.ai.production.sse.SseStreamWriter;
import com.feike.ai.production.sse.StreamEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * 编排层：把 lock → history → retrieve → generate → persist 串成一次 run，并按统一 SSE 契约发事件。
 * <p>
 * 这里只负责「顺序和事件」，不含任何检索、prompt 或 SQL 细节；反过来下面各层也完全不知道 SSE 的存在。
 * 拆开的直接好处是：换传输（比如将来加 WebSocket）不用碰 RAG 代码，改 RAG 也不会破坏事件契约。
 * <p>
 * 事件顺序固定为 {@code meta → sources → delta* → usage → done}，
 * 任何一步抛异常都收敛成单条 {@code error}。空检索直接发一条拒答 delta 后 done，
 * 不调 LLM——省掉一次必然产生幻觉风险的调用。
 *
 * <h2>会话写入的两条规则</h2>
 * <b>只有走到 done 才落库。</b> 取消和报错都不写：宁可丢一轮，也不能让历史里留下
 * 一条没有回答的孤儿 user 消息——它会一直参与后续每一轮的 prompt，错误不断放大。
 * <p>
 * <b>落库失败不改变已发出的结论。</b> 答案此刻已经流到用户屏幕上了，此时再发 error
 * 只会让人困惑「到底成没成」。所以持久化异常降级为 done 负载里的 {@code persisted:false}
 * 加一条 error 级日志，前端据此提示「本轮未计入历史」。
 */
public class ProductionChatService {

    private static final Logger log = LoggerFactory.getLogger(ProductionChatService.class);

    /** 同一会话已有一轮在进行时的错误码，前端据此提示而不是当成生成失败。 */
    public static final String SESSION_BUSY = "session_busy";

    private final ProductionRetrievalService retrieval;
    private final ProductionAnswerGenerator generator;
    private final ProductionProperties properties;
    private final ProductionChatSessionStore sessionStore;
    private final SessionLock sessionLock;

    /**
     * @param retrieval    检索层
     * @param generator    生成层
     * @param properties   生产链路配置
     * @param sessionStore 会话存储；会话功能关闭时为 {@code null}
     * @param sessionLock  会话锁；会话功能关闭时为 {@code null}
     */
    public ProductionChatService(
        ProductionRetrievalService retrieval,
        ProductionAnswerGenerator generator,
        ProductionProperties properties,
        ProductionChatSessionStore sessionStore,
        SessionLock sessionLock
    ) {
        this.retrieval = retrieval;
        this.generator = generator;
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.sessionLock = sessionLock;
    }

    /**
     * @return 多轮会话是否可用
     */
    public boolean sessionEnabled() {
        return properties.session().enabled() && sessionStore != null && sessionLock != null;
    }

    /**
     * 读取会话历史。
     *
     * @param sessionId 会话 id
     * @return 消息列表
     * @throws SessionDisabledException 会话功能未启用
     */
    public List<SessionMessage> history(String sessionId) {
        requireSession();
        return sessionStore.history(sessionId);
    }

    /**
     * 清空会话。
     *
     * @param sessionId 会话 id
     * @return 会话原先是否存在
     * @throws SessionDisabledException 会话功能未启用
     */
    public boolean clearSession(String sessionId) {
        requireSession();
        return sessionStore.clear(sessionId);
    }

    /**
     * 同步问答，不走 SSE。
     *
     * @param sessionId 会话 id；空则新建。会话功能关闭时忽略
     * @param question  用户问题
     * @param provider  Chat Provider id
     * @param topK      覆盖默认 topK
     * @return 答案与来源
     * @throws SessionBusyException 同一会话已有一轮在进行
     */
    public ChatAnswer answer(String sessionId, String question, String provider, Integer topK) {
        if (!sessionEnabled()) {
            return answerStateless(null, question, provider, topK, null);
        }
        String id = sessionStore.resolveSessionId(sessionId);
        try (SessionLock.Handle ignored = acquireOrThrow(id)) {
            return answerStateless(id, question, provider, topK, null);
        }
    }

    /**
     * 流式问答。调用方负责在虚拟线程上执行本方法，并在客户端断开时中断该线程。
     *
     * @param writer    事件出口
     * @param sessionId 会话 id；空则新建。会话功能关闭时忽略
     * @param question  用户问题
     * @param provider  Chat Provider id
     * @param topK      覆盖默认 topK
     */
    public void streamAnswer(
        SseStreamWriter writer,
        String sessionId,
        String question,
        String provider,
        Integer topK
    ) {
        if (!sessionEnabled()) {
            streamWithin(writer, null, question, provider, topK);
            return;
        }
        String id = sessionStore.resolveSessionId(sessionId);
        Optional<SessionLock.Handle> handle = sessionLock.tryAcquire(id);
        if (handle.isEmpty()) {
            // 不是生成失败，而是用户在同一会话里连点了两次。给专门的错误码，
            // 让前端能提示「上一轮还在进行」而不是笼统的「服务异常」。
            writer.error(SESSION_BUSY, "该会话已有一轮对话正在进行，请等待其完成后再试");
            return;
        }
        try (SessionLock.Handle ignored = handle.get()) {
            streamWithin(writer, id, question, provider, topK);
        }
    }

    private void streamWithin(
        SseStreamWriter writer,
        String sessionId,
        String question,
        String provider,
        Integer topK
    ) {
        try {
            History history = loadHistory(sessionId);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("question", question);
            meta.put("provider", provider);
            meta.put("corpus", properties.corpus());
            meta.put("sessionId", sessionId);
            meta.put("historyMessages", history.messages().size());
            meta.put("historyDropped", history.dropped());
            writer.meta(meta);

            ProductionRetrievalService.RetrievalResult hits = retrieval.retrieve(question, topK);
            Map<String, Object> sourcesPayload = new LinkedHashMap<>();
            sourcesPayload.put("sources", hits.sources());
            sourcesPayload.put("retrievalEmpty", hits.empty());
            sourcesPayload.put("retrievalMode", hits.retrievalMode());
            writer.emit(StreamEventType.sources, sourcesPayload);

            if (hits.empty()) {
                writer.delta(ProductionAnswerGenerator.EMPTY_REFUSAL);
                writer.emit(StreamEventType.usage, usage(0, 0));
                // 拒答也是一轮完整对话，同样入库：否则用户追问「为什么答不了」时，
                // 模型看不到自己上一轮说过什么
                boolean persisted = persist(sessionId, writer.runId(), question,
                    ProductionAnswerGenerator.EMPTY_REFUSAL);
                writer.done(doneBody(true, sessionId, persisted));
                return;
            }

            StringBuilder answer = new StringBuilder();
            generator.stream(question, provider, hits.hits(), history.messages(), chunk -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("客户端已断开");
                }
                answer.append(chunk);
                writer.delta(chunk);
            });

            writer.emit(StreamEventType.usage, usage(answer.length(), hits.sources().size()));
            boolean persisted = persist(sessionId, writer.runId(), question, answer.toString());
            writer.done(doneBody(false, sessionId, persisted));
        } catch (CancellationException ex) {
            // 半截回答不入库：留下没有 assistant 的 turn 会污染后续所有轮次
            log.info("run={} 被取消: {}", writer.runId(), ex.getMessage());
            writer.cancel();
        } catch (RuntimeException ex) {
            log.error("run={} 生成失败", writer.runId(), ex);
            writer.error("upstream_error", "生成失败：" + rootMessage(ex));
        }
    }

    private ChatAnswer answerStateless(
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String runId
    ) {
        History history = loadHistory(sessionId);
        ProductionRetrievalService.RetrievalResult hits = retrieval.retrieve(question, topK);
        String answer = hits.empty()
            ? ProductionAnswerGenerator.EMPTY_REFUSAL
            : generator.generate(question, provider, hits.hits(), history.messages());
        boolean persisted = persist(sessionId, runId, question, answer);
        return new ChatAnswer(
            sessionId,
            answer,
            hits.sources(),
            hits.empty(),
            hits.retrievalMode(),
            history.messages().size(),
            persisted
        );
    }

    /** 读历史并按预算裁剪；会话关闭时返回空。 */
    private History loadHistory(String sessionId) {
        if (sessionId == null || !sessionEnabled()) {
            return new History(List.of(), 0);
        }
        List<Message> stored = sessionStore.historyMessages(sessionId);
        ContextBudget.TrimResult trimmed = ContextBudget.trim(
            stored,
            properties.session().maxMessages(),
            properties.session().tokenBudget()
        );
        return new History(trimmed.messages(), trimmed.droppedCount());
    }

    /**
     * 落库一轮对话。
     *
     * @return 是否成功；会话关闭时返回 false
     */
    private boolean persist(String sessionId, String runId, String question, String answer) {
        if (sessionId == null || !sessionEnabled()) {
            return false;
        }
        try {
            sessionStore.appendTurn(sessionId, UUID.randomUUID(), runId, question, answer);
            return true;
        } catch (RuntimeException ex) {
            // 见类注释：答案已经发出去了，此时改口报错只会让人更困惑
            log.error("session={} run={} 本轮未能写入历史", sessionId, runId, ex);
            return false;
        }
    }

    private SessionLock.Handle acquireOrThrow(String sessionId) {
        return sessionLock.tryAcquire(sessionId).orElseThrow(() -> new SessionBusyException(sessionId));
    }

    private void requireSession() {
        if (!sessionEnabled()) {
            throw new SessionDisabledException();
        }
    }

    private static Map<String, Object> doneBody(boolean retrievalEmpty, String sessionId, boolean persisted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retrievalEmpty", retrievalEmpty);
        payload.put("sessionId", sessionId);
        payload.put("persisted", persisted);
        return payload;
    }

    private static Map<String, Object> usage(int answerChars, int sourceCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answerChars", answerChars);
        payload.put("sourceCount", sourceCount);
        return payload;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    /** 裁剪后的历史窗口。 */
    private record History(List<Message> messages, int dropped) {}

    /**
     * 同步问答结果。
     *
     * @param sessionId       会话 id；会话功能关闭时为 {@code null}
     * @param answer          答案正文
     * @param sources         检索来源
     * @param retrievalEmpty  是否空检索（此时 answer 是固定拒答）
     * @param retrievalMode   实际检索模式
     * @param historyMessages 本轮送入模型的历史条数
     * @param persisted       本轮是否已写入历史
     */
    public record ChatAnswer(
        String sessionId,
        String answer,
        List<ProductionSource> sources,
        boolean retrievalEmpty,
        String retrievalMode,
        int historyMessages,
        boolean persisted
    ) {}
}
