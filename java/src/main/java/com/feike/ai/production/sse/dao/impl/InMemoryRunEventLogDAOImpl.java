package com.feike.ai.production.sse.dao.impl;

import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内事件日志：仅用于单测与单实例演示。
 * <p>
 * 明确不适合生产：进程重启即丢、多实例之间不可见，
 * 也就是说重连打到另一个节点时一定续不上。生产请用 {@link RedisRunEventLogDAOImpl}。
 */
public class InMemoryRunEventLogDAOImpl implements RunEventLogDAO {

    private final Map<String, CopyOnWriteArrayList<StreamEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, RunStateEnum> states = new ConcurrentHashMap<>();

    @Override
    public void begin(String runId) {
        events.put(runId, new CopyOnWriteArrayList<>());
        states.put(runId, RunStateEnum.PENDING);
    }

    @Override
    public void append(String runId, StreamEvent event) {
        events.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(event);
        states.merge(runId, RunStateEnum.STREAMING, (existing, incoming) ->
            existing.terminal() ? existing : RunStateEnum.STREAMING);
    }

    @Override
    public void finish(String runId, RunStateEnum terminal) {
        if (terminal == null || !terminal.terminal()) {
            return;
        }
        states.put(runId, terminal);
    }

    @Override
    public Optional<RunSnapshot> snapshot(String runId) {
        RunStateEnum state = states.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        List<StreamEvent> stored = events.getOrDefault(runId, new CopyOnWriteArrayList<>());
        long lastSeq = stored.isEmpty() ? -1 : stored.getLast().seq();
        return Optional.of(new RunSnapshot(runId, state, lastSeq));
    }

    @Override
    public List<StreamEvent> replay(String runId, long afterSeq) {
        List<StreamEvent> stored = events.get(runId);
        if (stored == null) {
            return List.of();
        }
        List<StreamEvent> out = new ArrayList<>();
        for (StreamEvent event : stored) {
            if (event.seq() > afterSeq) {
                out.add(event);
            }
        }
        return List.copyOf(out);
    }
}
