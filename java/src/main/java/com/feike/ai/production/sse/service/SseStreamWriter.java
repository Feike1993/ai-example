package com.feike.ai.production.sse.service;

import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生产链路 SSE 的唯一出口，负责把 {@link StreamEventTypeEnum} 契约变成硬约束。
 * <p>
 * 它保证三件事，而这三件正是教学样例缺的：
 * <ol>
 *   <li>每条事件带单调递增的 seq，客户端能自己发现缺口；</li>
 *   <li>终态事件恰好一条且必须是最后一条，终态之后的写入直接丢弃并告警；</li>
 *   <li>所有写出的事件同时落到 {@link RunEventLogDAO}，断线重连才有东西可回放。</li>
 * </ol>
 * 写出失败（客户端已断开）不再向上抛：此时业务侧继续算下去也没人接收，
 * 但事件仍然记进日志，重连后可以补齐——这正是「断流不等于失败」的实现基础。
 */
public class SseStreamWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SseStreamWriter.class);

    private final String runId;
    private final EventSink sink;
    private final RunEventLogDAO eventLog;
    private final JsonMapper jsonMapper;
    private final AtomicLong nextSeq = new AtomicLong(0);
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile boolean sinkBroken;
    private volatile Runnable disconnectListener;

    /**
     * @param runId      本次 run 标识
     * @param sink       事件出口
     * @param eventLog   事件日志，用于断线续传
     * @param jsonMapper 事件负载序列化
     */
    public SseStreamWriter(String runId, EventSink sink, RunEventLogDAO eventLog, JsonMapper jsonMapper) {
        this.runId = runId;
        this.sink = sink;
        this.eventLog = eventLog;
        this.jsonMapper = jsonMapper;
        eventLog.begin(runId);
    }

    /**
     * @return 本次 run 标识
     */
    public String runId() {
        return runId;
    }

    /**
     * @return 是否已进入终态
     */
    public boolean terminated() {
        return terminated.get();
    }

    /**
     * 注册「客户端已断开」回调，只会触发一次。
     * <p>
     * 写出失败是这里唯一能察觉断开的时机——SseEmitter 的 onError 不一定被触发，
     * 因为 send 抛出的 IOException 被本类吞掉了。调用方据此中断上游 LLM 调用，
     * 否则一个已经没人看的回答会继续烧 token 直到生成结束。
     *
     * @param listener 断开回调
     */
    public void onDisconnect(Runnable listener) {
        this.disconnectListener = listener;
        if (sinkBroken) {
            listener.run();
        }
    }

    /**
     * 写出首条 meta 事件，把 runId 回传给客户端，供后续重连使用。
     *
     * @param payload 请求参数回显；可为 {@code null}
     */
    public void meta(Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        if (payload != null) {
            body.putAll(payload);
        }
        emit(StreamEventTypeEnum.META, body);
    }

    /**
     * 写出一条非终态事件。
     *
     * @param type    事件名；传终态类型会被拒绝，请改用 {@link #done} / {@link #error}
     * @param payload 事件负载，序列化为 JSON
     */
    public void emit(StreamEventTypeEnum type, Object payload) {
        if (type.terminal()) {
            throw new IllegalArgumentException("终态事件请走 done() / error()：" + type);
        }
        writeEvent(type, payload);
    }

    /**
     * 写出答案增量。单独开一个方法是因为 delta 的负载永远是裸字符串，
     * 调用点极多，避免每处都手工包一层 Map。
     *
     * @param text 增量文本；空串忽略
     */
    public void delta(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        writeEvent(StreamEventTypeEnum.DELTA, text);
    }

    /**
     * 正常收尾。重复调用无效。
     *
     * @param payload 收尾摘要，如是否触发截断；可为 {@code null}
     */
    public void done(Object payload) {
        if (!terminated.compareAndSet(false, true)) {
            log.warn("run={} 已终态，忽略重复 done", runId);
            return;
        }
        writeRaw(StreamEventTypeEnum.DONE, payload == null ? Map.of() : payload);
        eventLog.finish(runId, RunStateEnum.DONE);
        sink.complete();
    }

    /**
     * 异常收尾。重复调用无效。
     *
     * @param code    机器可读错误码，前端据此决定是否可重试
     * @param message 面向用户的中文提示
     */
    public void error(String code, String message) {
        if (!terminated.compareAndSet(false, true)) {
            log.warn("run={} 已终态，忽略 error({})", runId, code);
            return;
        }
        writeRaw(StreamEventTypeEnum.ERROR, Map.of(
            "code", code == null ? "internal_error" : code,
            "message", message == null ? "服务内部错误" : message
        ));
        eventLog.finish(runId, RunStateEnum.ERROR);
        sink.complete();
    }

    /**
     * 客户端断开或主动取消：只登记状态，不再往已死的连接写事件。
     */
    public void cancel() {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        eventLog.finish(runId, RunStateEnum.CANCELLED);
        sink.complete();
    }

    /**
     * 写心跳注释帧。不占 seq、不入日志。
     */
    public void heartbeat() {
        if (terminated.get() || sinkBroken) {
            return;
        }
        try {
            sink.heartbeat();
        } catch (IOException | RuntimeException ex) {
            markBroken(ex);
        }
    }

    /**
     * 关闭时若尚未终态，按取消处理，避免留下永远 STREAMING 的僵尸 run。
     */
    @Override
    public void close() {
        cancel();
    }

    private void writeEvent(StreamEventTypeEnum type, Object payload) {
        if (terminated.get()) {
            log.warn("run={} 已终态，丢弃 {} 事件", runId, type);
            return;
        }
        writeRaw(type, payload);
    }

    private void writeRaw(StreamEventTypeEnum type, Object payload) {
        String json = serialize(payload);
        StreamEvent event = new StreamEvent(nextSeq.getAndIncrement(), type, json);
        eventLog.append(runId, event);
        if (sinkBroken) {
            return;
        }
        try {
            sink.write(event);
        } catch (IOException | RuntimeException ex) {
            markBroken(ex);
        }
    }

    private void markBroken(Exception ex) {
        // 只记一次：客户端断开后每条 delta 都会撞到同一个异常，刷屏没有意义
        if (sinkBroken) {
            return;
        }
        sinkBroken = true;
        log.info("run={} 连接已断开，后续事件只入日志等待重连: {}", runId, ex.toString());
        Runnable listener = disconnectListener;
        if (listener != null) {
            listener.run();
        }
    }

    private String serialize(Object payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (RuntimeException ex) {
            log.warn("run={} 事件负载序列化失败，降级为空对象: {}", runId, ex.toString());
            return "{}";
        }
    }
}
