package com.feike.ai.production.chat;

import com.feike.ai.production.ProductionProperties;
import com.feike.ai.production.rag.ProductionSource;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.sse.SseStreamWriter;
import com.feike.ai.production.sse.StreamEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 编排层：把 retrieve → generate 串成一次 run，并按统一 SSE 契约发事件。
 * <p>
 * 这里只负责「顺序和事件」，不含任何检索或 prompt 细节；反过来三层服务也完全不知道 SSE 的存在。
 * 拆开的直接好处是：换传输（比如将来加 WebSocket）不用碰 RAG 代码，
 * 改 RAG 也不会破坏事件契约。
 * <p>
 * 事件顺序固定为 {@code meta → sources → delta* → usage → done}，
 * 任何一步抛异常都收敛成单条 {@code error}。空检索直接发一条拒答 delta 后 done，
 * 不调 LLM——省掉一次必然产生幻觉风险的调用。
 */
public class ProductionChatService {

    private static final Logger log = LoggerFactory.getLogger(ProductionChatService.class);

    private final ProductionRetrievalService retrieval;
    private final ProductionAnswerGenerator generator;
    private final ProductionProperties properties;

    /**
     * @param retrieval  检索层
     * @param generator  生成层
     * @param properties 生产链路配置
     */
    public ProductionChatService(
        ProductionRetrievalService retrieval,
        ProductionAnswerGenerator generator,
        ProductionProperties properties
    ) {
        this.retrieval = retrieval;
        this.generator = generator;
        this.properties = properties;
    }

    /**
     * 同步问答，不走 SSE。
     *
     * @param question 用户问题
     * @param provider Chat Provider id
     * @param topK     覆盖默认 topK
     * @return 答案与来源
     */
    public ChatAnswer answer(String question, String provider, Integer topK) {
        ProductionRetrievalService.RetrievalResult hits = retrieval.retrieve(question, topK);
        if (hits.empty()) {
            return new ChatAnswer(
                ProductionAnswerGenerator.EMPTY_REFUSAL,
                hits.sources(),
                true,
                hits.retrievalMode()
            );
        }
        String answer = generator.generate(question, provider, hits.hits());
        return new ChatAnswer(answer, hits.sources(), false, hits.retrievalMode());
    }

    /**
     * 流式问答。调用方负责在虚拟线程上执行本方法，并在客户端断开时中断该线程。
     *
     * @param writer   事件出口
     * @param question 用户问题
     * @param provider Chat Provider id
     * @param topK     覆盖默认 topK
     */
    public void streamAnswer(SseStreamWriter writer, String question, String provider, Integer topK) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("question", question);
            meta.put("provider", provider);
            meta.put("corpus", properties.corpus());
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
                writer.done(Map.of("retrievalEmpty", true));
                return;
            }

            int[] answerChars = {0};
            generator.stream(question, provider, hits.hits(), chunk -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("客户端已断开");
                }
                answerChars[0] += chunk.length();
                writer.delta(chunk);
            });

            writer.emit(StreamEventType.usage, usage(answerChars[0], hits.sources().size()));
            writer.done(Map.of("retrievalEmpty", false));
        } catch (CancellationException ex) {
            log.info("run={} 被取消: {}", writer.runId(), ex.getMessage());
            writer.cancel();
        } catch (RuntimeException ex) {
            log.error("run={} 生成失败", writer.runId(), ex);
            writer.error("upstream_error", "生成失败：" + rootMessage(ex));
        }
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

    /**
     * 同步问答结果。
     *
     * @param answer         答案正文
     * @param sources        检索来源
     * @param retrievalEmpty 是否空检索（此时 answer 是固定拒答）
     * @param retrievalMode  实际检索模式
     */
    public record ChatAnswer(
        String answer,
        List<ProductionSource> sources,
        boolean retrievalEmpty,
        String retrievalMode
    ) {}
}
