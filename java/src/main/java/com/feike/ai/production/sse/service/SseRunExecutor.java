package com.feike.ai.production.sse.service;

import com.feike.ai.production.chat.service.ProductionChatService;
import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * run 的生命周期管家：建 emitter、跑管线、发心跳、看门狗超时、断线续传。
 * <p>
 * 把这些横切关注点从业务服务里抽出来，是为了让 {@code ProductionChatService}
 * 只关心事件顺序。将来加 Agent 流、批处理流，复用这一个执行器即可，
 * 三条流的超时与续传行为天然一致。
 * <p>
 * 超时用自己的看门狗而不是 {@link SseEmitter} 的 timeout：容器超时会直接结束响应，
 * 客户端只看到连接断了，分不清是超时还是网络抖动；看门狗则能先发一条明确的
 * {@code error} 事件再收尾。emitter 自身的 timeout 设成看门狗时间加一段余量，只作兜底。
 */
public class SseRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(SseRunExecutor.class);

    /** emitter 兜底超时相对看门狗的余量，确保看门狗先动手。 */
    private static final long EMITTER_GRACE_MS = 30_000L;

    /** 续传轮询间隔：足够跟上 token 流，又不至于把 Redis 打满。 */
    private static final long TAIL_POLL_MS = 150L;

    private final RunEventLogDAO eventLog;
    private final JsonMapper jsonMapper;
    private final ProductionProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "prod-sse-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param eventLog   事件日志
     * @param jsonMapper 负载序列化
     * @param properties 心跳 / 超时配置
     */
    public SseRunExecutor(RunEventLogDAO eventLog, JsonMapper jsonMapper, ProductionProperties properties) {
        this.eventLog = eventLog;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /**
     * 启动一次新的 run。
     *
     * @param pipeline 业务管线，接收已配好的事件出口
     * @return 立即返回给客户端的 emitter
     */
    public SseEmitter start(Consumer<SseStreamWriter> pipeline) {
        return start(pipeline, runId -> { });
    }

    /**
     * 启动一次新的 run，并在工作线程启动前把 runId 回传给调用方（便于审计）。
     *
     * @param pipeline 业务管线
     * @param onBegin  拿到 runId；可空
     * @return emitter
     */
    public SseEmitter start(Consumer<SseStreamWriter> pipeline, Consumer<String> onBegin) {
        String runId = UUID.randomUUID().toString();
        if (onBegin != null) {
            onBegin.accept(runId);
        }
        long timeoutMs = properties.stream().timeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeoutMs + EMITTER_GRACE_MS);
        SseStreamWriter writer = new SseStreamWriter(runId, new SseEmitterSink(emitter), eventLog, jsonMapper);

        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        writer.onDisconnect(() -> cancelTask(taskRef));

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Future<?> task = workers.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                pipeline.accept(writer);
            } finally {
                MDC.clear();
                // 管线无论如何收场，都不能留下 STREAMING 的僵尸 run
                writer.close();
            }
        });
        taskRef.set(task);

        long heartbeatMs = properties.stream().heartbeatInterval().toMillis();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
            writer::heartbeat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> watchdog = scheduler.schedule(() -> {
            if (!writer.terminated()) {
                log.warn("run={} 超过 {}ms 未完成，按超时收尾", runId, timeoutMs);
                writer.error("stream_timeout", "生成超时，请重试或缩小问题范围");
                cancelTask(taskRef);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            watchdog.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            cancelTask(taskRef);
            writer.cancel();
        });
        emitter.onError(ex -> {
            cleanup.run();
            cancelTask(taskRef);
            writer.cancel();
        });
        return emitter;
    }

    /**
     * 断线续传：把 {@code lastEventId} 之后的事件补齐，若 run 仍在跑则继续跟随到终态。
     *
     * @param runId       原 run 标识
     * @param lastEventId 客户端已收到的最大 seq；首次连接传 -1
     * @return emitter
     * @throws BusinessException run 不存在或已过保留窗口时 410
     */
    public SseEmitter resume(String runId, long lastEventId) {
        RunSnapshot snapshot = eventLog.snapshot(runId)
            .orElseThrow(() -> new BusinessException(ErrorCodeEnum.RUN_GONE));

        long timeoutMs = properties.stream().timeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeoutMs + EMITTER_GRACE_MS);
        SseEmitterSink sink = new SseEmitterSink(emitter);
        long heartbeatMs = properties.stream().heartbeatInterval().toMillis();

        workers.submit(() -> tail(runId, lastEventId, snapshot, sink, timeoutMs, heartbeatMs));
        return emitter;
    }

    /**
     * 跟随事件流直到终态、超时或写出失败。
     * <p>
     * 用轮询而不是 Redis 的阻塞读（XREAD BLOCK）：轮询每 150ms 一次，
     * 对 token 级延迟完全够用，却省掉了「每条连接占一个阻塞 Redis 连接」的连接池压力。
     */
    private void tail(
        String runId,
        long lastEventId,
        RunSnapshot initial,
        SseEmitterSink sink,
        long timeoutMs,
        long heartbeatMs
    ) {
        long cursor = lastEventId;
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextHeartbeat = System.currentTimeMillis() + heartbeatMs;
        RunSnapshot snapshot = initial;
        try {
            while (true) {
                List<StreamEvent> pending = eventLog.replay(runId, cursor);
                for (StreamEvent event : pending) {
                    sink.write(event);
                    cursor = event.seq();
                    if (event.type().terminal()) {
                        return;
                    }
                }
                snapshot = eventLog.snapshot(runId).orElse(snapshot);
                if (snapshot.state().terminal() && cursor >= snapshot.lastSeq()) {
                    // CANCELLED 的 run 没有终态事件，补一条 error 让客户端有明确收尾
                    if (snapshot.state() == RunStateEnum.CANCELLED) {
                        sink.write(new StreamEvent(
                            cursor + 1,
                            StreamEventTypeEnum.ERROR,
                            "{\"code\":\"run_cancelled\",\"message\":\"该请求已被取消\"}"
                        ));
                    }
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    sink.write(new StreamEvent(
                        cursor + 1,
                        StreamEventTypeEnum.ERROR,
                        "{\"code\":\"stream_timeout\",\"message\":\"续传等待超时，请重新发起请求\"}"
                    ));
                    return;
                }
                if (System.currentTimeMillis() >= nextHeartbeat) {
                    sink.heartbeat();
                    nextHeartbeat = System.currentTimeMillis() + heartbeatMs;
                }
                Thread.sleep(TAIL_POLL_MS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            log.info("run={} 续传连接结束: {}", runId, ex.toString());
        } finally {
            sink.complete();
        }
    }

    private static void cancelTask(AtomicReference<Future<?>> taskRef) {
        Future<?> task = taskRef.get();
        if (task != null) {
            task.cancel(true);
        }
    }

    /**
     * 关闭内部线程池，避免应用关停时留下悬挂线程。
     */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        scheduler.shutdownNow();
    }
}
