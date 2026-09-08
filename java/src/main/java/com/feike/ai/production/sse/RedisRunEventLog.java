package com.feike.ai.production.sse;

import com.feike.ai.production.ProductionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 实现：事件进 Stream，状态进 Hash。
 * <p>
 * 为什么用 Stream 而不是 List：Stream 的 entry id 可以显式指定，这里固定用 {@code 1-<seq>}，
 * 于是「回放 seq 之后的事件」可以直接交给 XRANGE 做区间查询，不必把整条流拉回 JVM 再过滤。
 * 固定 ms 段为 1 是安全的——Redis 只要求 id 单调递增，而 seq 本身就单调递增。
 * <p>
 * 为什么状态要单独存一份而不是从最后一条事件推断：run 被取消时可能一条事件都没写出去，
 * 只有独立的状态字段才能表达「这次 run 存在过、但没有 done」。
 * <p>
 * 失败语义：Redis 不可用时抛 503 而不是让调用方静默降级。断线续传是本模块对外承诺的能力，
 * 悄悄退化成「不可恢复」比直接报错更难排查。
 */
public class RedisRunEventLog implements RunEventLog {

    private static final Logger log = LoggerFactory.getLogger(RedisRunEventLog.class);

    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_LAST_SEQ = "lastSeq";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /**
     * @param redis      字符串模板；事件与状态都以文本存储，便于 redis-cli 直接排查
     * @param properties 取 {@code app.production.stream.replay-ttl} 作为保留窗口
     */
    public RedisRunEventLog(StringRedisTemplate redis, ProductionProperties properties) {
        this.redis = redis;
        this.ttl = properties.stream().replayTtl();
    }

    @Override
    public void begin(String runId) {
        guard(() -> {
            Map<String, String> state = new LinkedHashMap<>();
            state.put(FIELD_STATE, RunState.PENDING.name());
            state.put(FIELD_LAST_SEQ, "-1");
            redis.opsForHash().putAll(stateKey(runId), state);
            redis.expire(stateKey(runId), ttl);
            return null;
        });
    }

    @Override
    public void append(String runId, StreamEvent event) {
        guard(() -> {
            Map<String, String> body = new LinkedHashMap<>();
            body.put(FIELD_SEQ, Long.toString(event.seq()));
            body.put(FIELD_TYPE, event.type().name());
            body.put(FIELD_DATA, event.data() == null ? "" : event.data());
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                .withStreamKey(eventsKey(runId))
                .withId(RecordId.of(recordId(event.seq()))));
            redis.expire(eventsKey(runId), ttl);

            Map<String, String> state = new LinkedHashMap<>();
            // 已是终态就不要被后到的 append 打回 STREAMING；SseStreamWriter 保证终态后不再写，
            // 这里只是多一层防御，避免并发下状态回退。
            if (!currentState(runId).terminal()) {
                state.put(FIELD_STATE, RunState.STREAMING.name());
            }
            state.put(FIELD_LAST_SEQ, Long.toString(event.seq()));
            redis.opsForHash().putAll(stateKey(runId), state);
            redis.expire(stateKey(runId), ttl);
            return null;
        });
    }

    @Override
    public void finish(String runId, RunState terminal) {
        if (terminal == null || !terminal.terminal()) {
            return;
        }
        guard(() -> {
            redis.opsForHash().put(stateKey(runId), FIELD_STATE, terminal.name());
            redis.expire(stateKey(runId), ttl);
            redis.expire(eventsKey(runId), ttl);
            return null;
        });
    }

    @Override
    public Optional<RunSnapshot> snapshot(String runId) {
        return guard(() -> {
            List<Object> values = redis.opsForHash()
                .multiGet(stateKey(runId), List.of(FIELD_STATE, FIELD_LAST_SEQ));
            Object rawState = values.isEmpty() ? null : values.getFirst();
            if (rawState == null) {
                return Optional.empty();
            }
            long lastSeq = parseSeq(values.size() > 1 ? values.get(1) : null);
            return Optional.of(new RunSnapshot(runId, parseState(rawState), lastSeq));
        });
    }

    @Override
    public List<StreamEvent> replay(String runId, long afterSeq) {
        return guard(() -> {
            Range<String> range = afterSeq < 0
                ? Range.unbounded()
                : Range.rightUnbounded(Range.Bound.exclusive(recordId(afterSeq)));
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(eventsKey(runId), range);
            if (records == null || records.isEmpty()) {
                return List.<StreamEvent>of();
            }
            List<StreamEvent> events = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> record : records) {
                StreamEvent event = toEvent(record);
                if (event != null && event.seq() > afterSeq) {
                    events.add(event);
                }
            }
            return List.copyOf(events);
        });
    }

    private RunState currentState(String runId) {
        Object raw = redis.opsForHash().get(stateKey(runId), FIELD_STATE);
        return raw == null ? RunState.PENDING : parseState(raw);
    }

    private static StreamEvent toEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        Object seq = value.get(FIELD_SEQ);
        Object type = value.get(FIELD_TYPE);
        if (seq == null || type == null) {
            return null;
        }
        try {
            Object data = value.get(FIELD_DATA);
            return new StreamEvent(
                Long.parseLong(String.valueOf(seq)),
                StreamEventType.valueOf(String.valueOf(type)),
                data == null ? "" : String.valueOf(data)
            );
        } catch (IllegalArgumentException ex) {
            // 未知事件名或坏 seq：跳过而不是让整次回放失败，剩下的事件仍然有价值
            log.warn("回放时跳过无法解析的事件: {}", ex.toString());
            return null;
        }
    }

    private static RunState parseState(Object raw) {
        try {
            return RunState.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return RunState.PENDING;
        }
    }

    private static long parseSeq(Object raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** 固定 ms 段，seq 段直接用事件序号，使 XRANGE 能按序号做区间。 */
    private static String recordId(long seq) {
        return "1-" + seq;
    }

    private static String eventsKey(String runId) {
        return "prod:run:" + runId + ":events";
    }

    private static String stateKey(String runId) {
        return "prod:run:" + runId + ":state";
    }

    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Redis 事件日志操作失败", ex);
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "事件日志暂不可用（Redis 连接异常），工业级流式接口无法保证断线续传"
            );
        }
    }
}
