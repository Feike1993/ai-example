package com.feike.ai.production.chat.service.impl;

import com.feike.ai.production.chat.service.SessionBusyException;
import com.feike.ai.production.chat.service.SessionDisabledException;
import com.feike.ai.production.session.service.SessionNotOwnedException;

import com.feike.ai.production.chat.service.ProductionChatService;

import com.feike.ai.core.context.ContextBudget;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.chat.model.ChatAttachments;
import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.chat.model.ChatImage;
import com.feike.ai.production.guardrail.service.GuardrailBlockedException;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.lock.manager.SessionLock;
import com.feike.ai.production.rag.model.ProductionSource;
import com.feike.ai.production.rag.generate.service.ProductionAnswerGenerator;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.rag.retrieve.model.ProductionRetrieveQuery;
import com.feike.ai.production.session.dao.ProductionChatSessionDAO;
import com.feike.ai.production.session.model.SessionMessageDO;
import com.feike.ai.production.sse.service.SseStreamWriter;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

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
public class ProductionChatServiceImpl implements ProductionChatService {

    private static final Logger log = LoggerFactory.getLogger(ProductionChatServiceImpl.class);

    /** 同一会话已有一轮在进行时的错误码，前端据此提示而不是当成生成失败。 */
    public static final String SESSION_BUSY = "session_busy";

    private final ProductionRetrievalService retrieval;
    private final ProductionAnswerGenerator generator;
    private final ProductionProperties properties;
    private final ProductionChatSessionDAO sessionStore;
    private final SessionLock sessionLock;
    private final ProductionGuardrail guardrail;

    /**
     * @param retrieval    检索层
     * @param generator    生成层
     * @param properties   生产链路配置
     * @param sessionStore 会话存储；会话功能关闭时为 {@code null}
     * @param sessionLock  会话锁；会话功能关闭时为 {@code null}
     * @param guardrail    护栏；测试可传 {@code null} 跳过
     */
    public ProductionChatServiceImpl(
        ProductionRetrievalService retrieval,
        ProductionAnswerGenerator generator,
        ProductionProperties properties,
        ProductionChatSessionDAO sessionStore,
        SessionLock sessionLock,
        ProductionGuardrail guardrail
    ) {
        this.retrieval = retrieval;
        this.generator = generator;
        this.properties = properties;
        this.sessionStore = sessionStore;
        this.sessionLock = sessionLock;
        this.guardrail = guardrail;
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
     * @param principal 当前用户
     * @param sessionId 会话 id
     * @return 消息列表
     * @throws SessionDisabledException 会话功能未启用
     * @throws com.feike.ai.production.session.service.SessionNotOwnedException 不属于本租户
     */
    public List<SessionMessageDO> history(ProductionPrincipal principal, String sessionId) {
        requireSession();
        if (!sessionStore.ownedBy(principal.tenantId(), sessionId)) {
            throw new com.feike.ai.production.session.service.SessionNotOwnedException(sessionId);
        }
        return sessionStore.history(principal.tenantId(), sessionId);
    }

    /**
     * 清空会话。
     *
     * @param principal 当前用户
     * @param sessionId 会话 id
     * @return 会话原先是否存在
     * @throws SessionDisabledException 会话功能未启用
     */
    public boolean clearSession(ProductionPrincipal principal, String sessionId) {
        requireSession();
        return sessionStore.clear(principal.tenantId(), sessionId);
    }

    /**
     * 同步问答，不走 SSE。
     *
     * @param principal 当前用户
     * @param sessionId 会话 id；空则新建。会话功能关闭时忽略
     * @param question  用户问题
     * @param provider  Chat Provider id
     * @param topK      覆盖默认 topK
     * @return 答案与来源
     * @throws SessionBusyException 同一会话已有一轮在进行
     */
    public ChatAnswer answer(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK
    ) {
        return answer(principal, sessionId, question, provider, topK, null);
    }

    /**
     * 同步问答，不走 SSE。
     *
     * @param principal      当前用户
     * @param sessionId      会话 id；空则新建。会话功能关闭时忽略
     * @param question       用户问题
     * @param provider       Chat Provider id
     * @param topK           覆盖默认 topK
     * @param queryExpansion none / rewrite / hyde；空则用配置默认
     * @return 答案与来源
     * @throws SessionBusyException 同一会话已有一轮在进行
     */
    public ChatAnswer answer(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion
    ) {
        checkInput(question);
        if (!sessionEnabled()) {
            return answerStateless(principal, null, question, provider, topK, null, queryExpansion);
        }
        String id = sessionStore.resolveSessionId(sessionId);
        rejectForeignSession(principal, sessionId, id);
        try (SessionLock.Handle ignored = acquireOrThrow(id)) {
            return answerStateless(principal, id, question, provider, topK, null, queryExpansion);
        }
    }

    /**
     * 流式问答。调用方负责在虚拟线程上执行本方法，并在客户端断开时中断该线程。
     *
     * @param writer    事件出口
     * @param principal 当前用户
     * @param sessionId 会话 id；空则新建。会话功能关闭时忽略
     * @param question  用户问题
     * @param provider  Chat Provider id
     * @param topK      覆盖默认 topK
     */
    public void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK
    ) {
        streamAnswer(writer, principal, sessionId, question, provider, topK, null);
    }

    /**
     * 流式问答。调用方负责在虚拟线程上执行本方法，并在客户端断开时中断该线程。
     *
     * @param writer         事件出口
     * @param principal      当前用户
     * @param sessionId      会话 id；空则新建。会话功能关闭时忽略
     * @param question       用户问题
     * @param provider       Chat Provider id
     * @param topK           覆盖默认 topK
     * @param queryExpansion none / rewrite / hyde；空则用配置默认
     */
    public void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion
    ) {
        streamAnswer(writer, principal, sessionId, question, provider, topK, queryExpansion, List.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion,
        byte[] imageBytes,
        String imageMime
    ) {
        streamAnswer(writer, principal, sessionId, question, provider, topK, queryExpansion,
            ChatImage.ofNullable(imageBytes, imageMime));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void streamAnswer(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String queryExpansion,
        List<ChatImage> images
    ) {
        List<ChatImage> frozenImages = images == null ? List.of() : List.copyOf(images);
        streamAnswer(writer, principal, sessionId, question, provider, topK, queryExpansion,
            frozenImages, List.of(), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void streamAnswer(
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
    ) {
        List<ChatImage> frozenImages = images == null ? List.of() : List.copyOf(images);
        List<ChatDocument> frozenDocs = documents == null ? List.of() : List.copyOf(documents);
        try {
            checkInput(question);
        } catch (GuardrailBlockedException ex) {
            writer.error(ex.code(), ex.getMessage());
            return;
        }
        if (!sessionEnabled()) {
            streamWithin(writer, principal, null, question, provider, topK, queryExpansion,
                frozenImages, frozenDocs, ocrUsed);
            return;
        }
        String id = sessionStore.resolveSessionId(sessionId);
        try {
            rejectForeignSession(principal, sessionId, id);
        } catch (SessionNotOwnedException ex) {
            writer.error("session_not_found", "会话不存在");
            return;
        }
        Optional<SessionLock.Handle> handle = sessionLock.tryAcquire(id);
        if (handle.isEmpty()) {
            // 不是生成失败，而是用户在同一会话里连点了两次。给专门的错误码，
            // 让前端能提示「上一轮还在进行」而不是笼统的「服务异常」。
            writer.error(SESSION_BUSY, "该会话已有一轮对话正在进行，请等待其完成后再试");
            return;
        }
        try (SessionLock.Handle ignored = handle.get()) {
            streamWithin(writer, principal, id, question, provider, topK, queryExpansion,
                frozenImages, frozenDocs, ocrUsed);
        }
    }

    private void streamWithin(
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
    ) {
        int imageCount = images == null ? 0 : images.size();
        int documentCount = documents == null ? 0 : documents.size();
        boolean hasImage = imageCount > 0;
        boolean hasDocument = documentCount > 0;
        // 如果有图片，则优先使用图片提供者
        String effectiveProvider = provider;
        if (hasImage && (effectiveProvider == null || effectiveProvider.isBlank())) {
            effectiveProvider = properties.media().visionProvider();
        }
        try {
            History history = loadHistory(principal, sessionId);
            org.slf4j.MDC.put("runId", writer.runId());

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("provider", effectiveProvider);
            meta.put("corpus", properties.corpus());
            meta.put("sessionId", sessionId);
            meta.put("tenant", principal.tenantId());
            meta.put("historyMessages", history.messages().size());
            meta.put("historyDropped", history.dropped());
            meta.put("hasImage", hasImage);
            meta.put("imageCount", imageCount);
            meta.put("hasDocument", hasDocument);
            meta.put("documentCount", documentCount);
            meta.put("extractedChars", extractedChars(documents));
            meta.put("ocrUsed", ocrUsed);
            String traceId = org.slf4j.MDC.get("traceId");
            if (traceId != null) {
                meta.put("traceId", traceId);
            }
            writer.meta(meta);

            ProductionRetrievalService.RetrievalResult hits = retrieval.retrieve(
                new ProductionRetrieveQuery(question, topK, principal.tenantId(), queryExpansion, effectiveProvider));
            Map<String, Object> sourcesPayload = new LinkedHashMap<>();
            sourcesPayload.put("sources", hits.sources());
            sourcesPayload.put("retrievalEmpty", hits.empty());
            sourcesPayload.put("retrievalMode", hits.retrievalMode());
            sourcesPayload.put("queryExpansion", hits.queryExpansion());
            if (hits.rewrittenQuery() != null) {
                sourcesPayload.put("rewrittenQuery", hits.rewrittenQuery());
            }
            writer.emit(StreamEventTypeEnum.SOURCES, sourcesPayload);

            // 无图且无文档时空检索仍短路拒答。有附件时附件本身就是上下文，否则本机选跑永远打不到模型。
            if (hits.empty() && !hasImage && !hasDocument) {
                writer.delta(ProductionAnswerGenerator.EMPTY_REFUSAL);
                writer.emit(StreamEventTypeEnum.USAGE, usage(0, 0));
                // 拒答也是一轮完整对话，同样入库：否则用户追问「为什么答不了」时，
                // 模型看不到自己上一轮说过什么
                boolean persisted = persist(principal, sessionId, writer.runId(), question,
                    ProductionAnswerGenerator.EMPTY_REFUSAL);
                writer.done(doneBody(true, sessionId, persisted));
                return;
            }

            StringBuilder answer = new StringBuilder();
            Consumer<String> onChunk = chunk -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("客户端已断开");
                }
                answer.append(chunk);
                writer.delta(chunk);
            };
            if (hasDocument) {
                generator.stream(question, effectiveProvider, hits.hits(), history.messages(),
                    ChatAttachments.of(images, documents), onChunk);
            } else if (imageCount == 1) {
                ChatImage one = images.get(0);
                generator.stream(question, effectiveProvider, hits.hits(), history.messages(),
                    one.bytes(), one.mime(), onChunk);
            } else if (imageCount > 1) {
                generator.stream(question, effectiveProvider, hits.hits(), history.messages(),
                    images, onChunk);
            } else {
                generator.stream(question, effectiveProvider, hits.hits(), history.messages(), onChunk);
            }
            checkGenerated(answer.toString(), hits.sources());

            writer.emit(StreamEventTypeEnum.USAGE, usage(answer.length(), hits.sources().size()));
            boolean persisted = persist(principal, sessionId, writer.runId(),
                persistQuestion(question, imageCount, documentCount),
                answer.toString());
            writer.done(doneBody(false, sessionId, persisted));
        } catch (CancellationException ex) {
            // 半截回答不入库：留下没有 assistant 的 turn 会污染后续所有轮次
            log.info("run={} 被取消: {}", writer.runId(), ex.getMessage());
            writer.cancel();
        } catch (GuardrailBlockedException ex) {
            writer.error(ex.code(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("run={} 生成失败", writer.runId(), ex);
            writer.error("upstream_error", "生成失败，请稍后重试");
        }
    }

    private ChatAnswer answerStateless(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        Integer topK,
        String runId,
        String queryExpansion
    ) {
        History history = loadHistory(principal, sessionId);
        ProductionRetrievalService.RetrievalResult hits = retrieval.retrieve(
            new ProductionRetrieveQuery(question, topK, principal.tenantId(), queryExpansion, provider));
        String answer;
        if (hits.empty()) {
            answer = ProductionAnswerGenerator.EMPTY_REFUSAL;
        } else {
            answer = generator.generate(question, provider, hits.hits(), history.messages());
            checkGenerated(answer, hits.sources());
        }
        boolean persisted = persist(principal, sessionId, runId, question, answer);
        return new ChatAnswer(
            sessionId,
            answer,
            hits.sources(),
            hits.empty(),
            hits.retrievalMode(),
            history.messages().size(),
            persisted,
            hits.queryExpansion()
        );
    }

    /**
     * 落库问句：有图 / 文档只写占位前缀，不写二进制也不写抽出正文。刷新后跟问不再带原件。
     *
     * @param question   用户原句
     * @param imageCount 本轮张数
     * @return 写入会话的文本
     */
    public static String persistQuestion(String question, int imageCount) {
        return persistQuestion(question, imageCount, 0);
    }

    /**
     * 落库问句：在 {@code [图片]} / {@code [图片×N]} 之后追加 {@code [文档]} / {@code [文档×N]}。
     *
     * @param question      用户原句
     * @param imageCount    本轮张数
     * @param documentCount 本轮文档件数
     * @return 写入会话的文本
     */
    public static String persistQuestion(String question, int imageCount, int documentCount) {
        String text = question == null ? "" : question;
        if (imageCount > 0 && !text.startsWith("[图片")) {
            if (imageCount == 1) {
                text = "[图片] " + text;
            } else {
                text = "[图片×" + imageCount + "] " + text;
            }
        }
        if (documentCount > 0 && !hasDocumentPrefix(text)) {
            String docPrefix = documentCount == 1 ? "[文档] " : "[文档×" + documentCount + "] ";
            if (text.startsWith("[图片")) {
                int close = text.indexOf(']');
                int after = close < 0 ? 0 : close + 1;
                if (after < text.length() && text.charAt(after) == ' ') {
                    after++;
                }
                text = text.substring(0, after) + docPrefix + text.substring(after);
            } else {
                text = docPrefix + text;
            }
        }
        return text;
    }

    private static boolean hasDocumentPrefix(String text) {
        return text.startsWith("[文档") || text.contains(" [文档");
    }

    private static int extractedChars(List<ChatDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatDocument document : documents) {
            String text = document.extractedText();
            total += text == null ? 0 : text.length();
        }
        return total;
    }

    /** 读历史并按预算裁剪；会话关闭时返回空。 */
    private History loadHistory(ProductionPrincipal principal, String sessionId) {
        if (sessionId == null || !sessionEnabled()) {
            return new History(List.of(), 0);
        }
        List<Message> stored = sessionStore.historyMessages(principal.tenantId(), sessionId);
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
    private boolean persist(
        ProductionPrincipal principal,
        String sessionId,
        String runId,
        String question,
        String answer
    ) {
        if (sessionId == null || !sessionEnabled()) {
            return false;
        }
        try {
            sessionStore.appendTurn(
                principal.tenantId(), sessionId, UUID.randomUUID(), runId, question, answer);
            return true;
        } catch (RuntimeException ex) {
            // 见类注释：答案已经发出去了，此时改口报错只会让人更困惑
            log.error("session={} run={} 本轮未能写入历史", sessionId, runId, ex);
            return false;
        }
    }

    private void checkInput(String question) {
        if (guardrail != null) {
            guardrail.checkInput(question);
        }
    }

    private void checkGenerated(String answer, List<ProductionSource> sources) {
        if (guardrail == null) {
            return;
        }
        guardrail.checkOutput(answer);
        guardrail.checkCitations(answer, sources);
    }

    private static final int DEFAULT_LOCK_PROBE_HOLD_MS = 3_000;
    private static final int MAX_LOCK_PROBE_HOLD_MS = 10_000;

    /**
     * 占用会话锁一段时间后释放。给多实例演示用，不调 LLM。
     *
     * @param principal 当前用户
     * @param sessionId 会话；空则新建
     * @param holdMs    持锁毫秒
     * @return 实际 sessionId 与持锁时长
     */
    @Override
    public ProductionChatService.LockHold probeLock(ProductionPrincipal principal, String sessionId, Integer holdMs) {
        requireSession();
        String id = sessionStore.resolveSessionId(sessionId);
        rejectForeignSession(principal, sessionId, id);
        int ms = clampHoldMs(holdMs);
        try (SessionLock.Handle ignored = acquireOrThrow(id)) {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会话锁探针被中断", ex);
        }
        return new ProductionChatService.LockHold(id, ms);
    }

    private static int clampHoldMs(Integer holdMs) {
        if (holdMs == null || holdMs < 1) {
            return DEFAULT_LOCK_PROBE_HOLD_MS;
        }
        return Math.min(holdMs, MAX_LOCK_PROBE_HOLD_MS);
    }

    private SessionLock.Handle acquireOrThrow(String sessionId) {
        return sessionLock.tryAcquire(sessionId).orElseThrow(() -> new SessionBusyException(sessionId));
    }

    /**
     * 客户端带来的 sessionId 若已存在且不属于本租户，在抢锁之前拒绝。
     * 尚未建档的 id 允许认领，与 {@code appendTurn} 首次插入语义一致。
     */
    private void rejectForeignSession(ProductionPrincipal principal, String requested, String resolved) {
        if (sessionStore == null || requested == null || requested.isBlank()) {
            return;
        }
        if (sessionStore.exists(resolved) && !sessionStore.ownedBy(principal.tenantId(), resolved)) {
            throw new SessionNotOwnedException(resolved);
        }
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

    /** 裁剪后的历史窗口。 */
    private record History(List<Message> messages, int dropped) {}

}
