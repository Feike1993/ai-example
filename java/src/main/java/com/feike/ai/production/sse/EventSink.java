package com.feike.ai.production.sse;

import java.io.IOException;

/**
 * 事件出口抽象：把「往哪儿写」从「怎么定序」里分离出来。
 * <p>
 * 这样 {@link SseStreamWriter} 的 seq 递增、终态唯一等约束可以用普通单测覆盖，
 * 不必启动 Web 容器去构造一个真实的 SseEmitter。
 */
public interface EventSink {

    /**
     * 写出一条事件。
     *
     * @param event 已定序的事件
     * @throws IOException 连接已断开
     */
    void write(StreamEvent event) throws IOException;

    /**
     * 写出心跳注释帧（SSE 的 {@code :} 行），不占用 seq。
     *
     * @throws IOException 连接已断开
     */
    void heartbeat() throws IOException;

    /**
     * 正常关闭连接。
     */
    void complete();
}
