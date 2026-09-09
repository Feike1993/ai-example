package com.feike.ai.production.sse.service;

import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试替身：把写出的事件记在内存里，可切换成「连接已断开」。
 */
class RecordingSink implements EventSink {

    private final List<StreamEvent> written = new ArrayList<>();
    private int heartbeats;
    private boolean completed;
    private boolean broken;

    @Override
    public void write(StreamEvent event) throws IOException {
        if (broken) {
            throw new IOException("客户端已断开");
        }
        written.add(event);
    }

    @Override
    public void heartbeat() throws IOException {
        if (broken) {
            throw new IOException("客户端已断开");
        }
        heartbeats++;
    }

    @Override
    public void complete() {
        completed = true;
    }

    void breakConnection() {
        broken = true;
    }

    List<StreamEvent> written() {
        return List.copyOf(written);
    }

    List<StreamEventTypeEnum> types() {
        return written.stream().map(StreamEvent::type).toList();
    }

    int heartbeats() {
        return heartbeats;
    }

    boolean completed() {
        return completed;
    }
}
