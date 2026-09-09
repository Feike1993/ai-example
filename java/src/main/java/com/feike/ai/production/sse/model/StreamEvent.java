package com.feike.ai.production.sse.model;

/**
 * 一条已定序的 SSE 事件。
 *
 * @param seq  从 0 开始单调递增的序号，写进 SSE 的 {@code id:} 字段，重连时作为 Last-Event-ID
 * @param type 事件名
 * @param data 已序列化好的 JSON 字符串；这里不放对象是为了让回放路径无需重新序列化，
 *             回放出去的字节与首次推送完全一致
 */
public record StreamEvent(long seq, StreamEventTypeEnum type, String data) {
}
