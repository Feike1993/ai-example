package com.feike.ai.production.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 把事件写进 Spring MVC 的 {@link SseEmitter}。
 * <p>
 * 用 SseEmitter 而不是样例里的 {@code Flux<ServerSentEvent>}：本模块需要显式控制
 * {@code id:} 字段（Last-Event-ID 续传的载体）、心跳注释帧，以及在客户端断开时
 * 立刻回调取消上游 LLM 调用，这些在 Flux 返回值上都得绕。
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
            .name(event.type().name())
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
