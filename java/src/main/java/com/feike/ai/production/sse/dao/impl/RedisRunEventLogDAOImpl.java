package com.feike.ai.production.sse.dao.impl;

import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
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
 * <p>
 * begin / append / finish 走 Lua：XADD 与 lastSeq、TTL 同一次脚本完成，
 * 避免 Redis 抖动时事件已写入但状态 Hash 没跟上，续传出现缺口。
 */
public class RedisRunEventLogDAOImpl implements RunEventLogDAO {

    private static final Logger log = LoggerFactory.getLogger(RedisRunEventLogDAOImpl.class);

    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_LAST_SEQ = "lastSeq";
    private static final String FIELD_TENANT = "tenantId";

    /**
     * 登记 run：状态 Hash + TTL 一次完成，避免只写出字段却没过期。
     * KEYS[1]=state；ARGV=state,lastSeq,tenant,ttlMs。
     */
    private static final RedisScript<Long> BEGIN_SCRIPT = new DefaultRedisScript<>(
        """
            redis.call('hset', KEYS[1], 'state', ARGV[1], 'lastSeq', ARGV[2])
            if ARGV[3] ~= '' then
              redis.call('hset', KEYS[1], 'tenantId', ARGV[3])
            end
            redis.call('pexpire', KEYS[1], tonumber(ARGV[4]))
            return 1
            """,
        Long.class
    );

    /**
     * 追加事件：XADD、刷新 lastSeq、非终态才标 STREAMING、两条 key 续期，全部在脚本内。
     * KEYS=events,state；ARGV=recordId,seq,type,data,ttlMs。
     */
    private static final RedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>(
        """
            redis.call('xadd', KEYS[1], ARGV[1], 'seq', ARGV[2], 'type', ARGV[3], 'data', ARGV[4])
            redis.call('pexpire', KEYS[1], tonumber(ARGV[5]))
            local current = redis.call('hget', KEYS[2], 'state')
            if current ~= 'DONE' and current ~= 'ERROR' and current ~= 'CANCELLED' then
              redis.call('hset', KEYS[2], 'state', 'STREAMING')
            end
            redis.call('hset', KEYS[2], 'lastSeq', ARGV[2])
            redis.call('pexpire', KEYS[2], tonumber(ARGV[5]))
            return 1
            """,
        Long.class
    );

    /**
     * 终态：写 state 并为事件流续期。
     * KEYS=state,events；ARGV=terminal,ttlMs。
     */
    private static final RedisScript<Long> FINISH_SCRIPT = new DefaultRedisScript<>(
        """
            redis.call('hset', KEYS[1], 'state', ARGV[1])
            redis.call('pexpire', KEYS[1], tonumber(ARGV[2]))
            redis.call('pexpire', KEYS[2], tonumber(ARGV[2]))
            return 1
            """,
        Long.class
    );

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /**
     * @param redis      字符串模板；事件与状态都以文本存储，便于 redis-cli 直接排查
     * @param properties 取 {@code app.production.stream.replay-ttl} 作为保留窗口
     */
    public RedisRunEventLogDAOImpl(StringRedisTemplate redis, ProductionProperties properties) {
        this.redis = redis;
        this.ttl = properties.stream().replayTtl();
    }

    @Override
    public void begin(String runId, String tenantId) {
        guard(() -> {
            String tenant = tenantId == null || tenantId.isBlank() ? "" : tenantId.trim();
            redis.execute(
                BEGIN_SCRIPT,
                List.of(stateKey(runId)),
                RunStateEnum.PENDING.name(),
                "-1",
                tenant,
                String.valueOf(ttl.toMillis())
            );
            return null;
        });
    }

    @Override
    public void append(String runId, StreamEvent event) {
        guard(() -> {
            redis.execute(
                APPEND_SCRIPT,
                List.of(eventsKey(runId), stateKey(runId)),
                recordId(event.seq()),
                Long.toString(event.seq()),
                event.type().getCode(),
                event.data() == null ? "" : event.data(),
                String.valueOf(ttl.toMillis())
            );
            return null;
        });
    }

    @Override
    public void finish(String runId, RunStateEnum terminal) {
        if (terminal == null || !terminal.terminal()) {
            return;
        }
        guard(() -> {
            redis.execute(
                FINISH_SCRIPT,
                List.of(stateKey(runId), eventsKey(runId)),
                terminal.name(),
                String.valueOf(ttl.toMillis())
            );
            return null;
        });
    }

    @Override
    public Optional<RunSnapshot> snapshot(String runId) {
        return guard(() -> {
            List<Object> values = redis.opsForHash()
                .multiGet(stateKey(runId), List.of(FIELD_STATE, FIELD_LAST_SEQ, FIELD_TENANT));
            Object rawState = values.isEmpty() ? null : values.getFirst();
            if (rawState == null) {
                return Optional.empty();
            }
            long lastSeq = parseSeq(values.size() > 1 ? values.get(1) : null);
            String tenant = values.size() > 2 ? blankToNull(values.get(2)) : null;
            return Optional.of(new RunSnapshot(runId, parseState(rawState), lastSeq, tenant));
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
                StreamEventTypeEnum.fromWire(String.valueOf(type)),
                data == null ? "" : String.valueOf(data)
            );
        } catch (IllegalArgumentException ex) {
            // 未知事件名或坏 seq：跳过而不是让整次回放失败，剩下的事件仍然有价值
            log.warn("回放时跳过无法解析的事件: {}", ex.toString());
            return null;
        }
    }

    private static RunStateEnum parseState(Object raw) {
        try {
            return RunStateEnum.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException ex) {
            return RunStateEnum.PENDING;
        }
    }

    private static String blankToNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
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
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Redis 事件日志操作失败", ex);
            throw new BusinessException(ErrorCodeEnum.EVENT_LOG_UNAVAILABLE);
        }
    }
}
