package com.feike.ai.production.sse.service;

import com.feike.ai.production.sse.model.StreamEvent;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 把事件写进 Spring MVC 的 {@link SseEmitter}。
 * <p>
 * 用 SseEmitter 而不是样例里的 {@code Flux<ServerSentEvent>}：本模块需要显式控制
 * {@code id:} 字段（Last-Event-ID 续传的载体）和心跳注释帧。
 * 客户端断开只关闭当前传输，不取消后台 run；后续事件仍写入日志供续传。
 */
public class SseEmitterSink implements EventSink {

    private final SseEmitter emitter;

    /**
     * @param emitter 已交给 Spring MVC 的 emitter
     */
    public SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void write(StreamEvent event) throws IOException {
        emitter.send(SseEmitter.event()
            .id(Long.toString(event.seq()))
            .name(event.type().getCode())
            .data(event.data()));
    }

    @Override
    public void heartbeat() throws IOException {
        emitter.send(SseEmitter.event().comment("hb"));
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            // 连接已被容器回收时 complete 会抛；此时无事可做，也不该影响业务收尾
        }
    }
}
