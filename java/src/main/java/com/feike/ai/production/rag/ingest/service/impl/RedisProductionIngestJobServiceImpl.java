package com.feike.ai.production.rag.ingest.service.impl;

import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.model.IngestJobStatusEnum;
import com.feike.ai.production.rag.ingest.model.IngestJobVO;
import com.feike.ai.production.rag.ingest.service.ProductionIngestJobService;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 入库管道：投递进 Stream，consumer group 保证双实例只消费一次。
 * <p>
 * 任务状态单独放 Hash，是因为 XREAD 只能看到还没 ACK 的消息，完成后前端仍要能查
 * chunkCount。Stream 负责「谁来做」，Hash 负责「做到哪了」。
 */
public class RedisProductionIngestJobServiceImpl implements ProductionIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(RedisProductionIngestJobServiceImpl.class);

    static final String STREAM_KEY = "prod:ingest:stream";
    static final String GROUP = "prod-ingest";
    static final String LATEST_KEY = "prod:ingest:latest";
    static final String JOB_KEY_PREFIX = "prod:ingest:job:";
    static final String LOCK_KEY = "prod:ingest:lock";

    private static final String FIELD_JOB_ID = "jobId";
    private static final Duration JOB_TTL = Duration.ofHours(1);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
        """
        if redis.call('get', KEYS[1]) == ARGV[1] then
          return redis.call('del', KEYS[1])
        else
          return 0
        end
        """,
        Long.class
    );

    private final ProductionIngestService ingest;
    private final StringRedisTemplate redis;
    private final ProductionProperties properties;
    private final ProductionInstanceIdentity identity;
    private final ProductionMetrics metrics;
    private final JsonMapper jsonMapper;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prod-ingest-consumer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param ingest     实际建索引
     * @param redis      Redis
     * @param properties corpus
     * @param identity   消费组内的 consumer 名
     * @param metrics    可空
     * @param jsonMapper sources 序列化
     */
    public RedisProductionIngestJobServiceImpl(
        ProductionIngestService ingest,
        StringRedisTemplate redis,
        ProductionProperties properties,
        ProductionInstanceIdentity identity,
        ProductionMetrics metrics,
        JsonMapper jsonMapper
    ) {
        this.ingest = ingest;
        this.redis = redis;
        this.properties = properties;
        this.identity = identity;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        ensureGroup();
        worker.execute(this::consumeLoop);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IngestJobVO submit() {
        String active = redis.opsForValue().get(LOCK_KEY);
        if (active != null && !active.isBlank()) {
            try {
                IngestJobVO existing = get(active);
                if (!IngestJobStatusEnum.from(existing.status()).terminal()) {
                    return existing;
                }
            } catch (BusinessException ignored) {
                redis.delete(LOCK_KEY);
            }
        }
        String jobId = UUID.randomUUID().toString();
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, jobId, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            String winner = redis.opsForValue().get(LOCK_KEY);
            if (winner != null) {
                return get(winner);
            }
        }
        IngestJobVO queued = new IngestJobVO(
            jobId,
            IngestJobStatusEnum.QUEUED.getCode(),
            properties.corpus(),
            null,
            List.of(),
            null,
            null
        );
        save(queued);
        redis.opsForValue().set(LATEST_KEY, jobId, JOB_TTL);
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(FIELD_JOB_ID, jobId)).withStreamKey(STREAM_KEY));
        if (metrics != null) {
            metrics.ingestSubmitted();
        }
        return queued;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IngestJobVO get(String jobId) {
        Map<Object, Object> raw = redis.opsForHash().entries(jobKey(jobId));
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.INGEST_JOB_NOT_FOUND);
        }
        return toView(raw);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<IngestJobVO> getLatest() {
        String id = redis.opsForValue().get(LATEST_KEY);
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(get(id));
        } catch (BusinessException ex) {
            return Optional.empty();
        }
    }

    private void consumeLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                    Consumer.from(GROUP, identity.id()),
                    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handleRecord(record);
                }
            } catch (RuntimeException ex) {
                if (running.get()) {
                    log.warn("入库消费循环异常: {}", ex.toString());
                    sleepQuietly();
                }
            }
        }
    }

    private void handleRecord(MapRecord<String, Object, Object> record) {
        Object rawId = record.getValue() == null ? null : record.getValue().get(FIELD_JOB_ID);
        String jobId = rawId == null ? null : String.valueOf(rawId);
        RecordId recordId = record.getId();
        if (jobId == null || jobId.isBlank()) {
            ack(recordId);
            return;
        }
        try {
            runJob(jobId);
        } finally {
            ack(recordId);
            releaseLock(jobId);
        }
    }

    private void runJob(String jobId) {
        IngestJobVO current;
        try {
            current = get(jobId);
        } catch (BusinessException ex) {
            log.warn("消费到未知入库任务 {}", jobId);
            return;
        }
        save(new IngestJobVO(
            jobId,
            IngestJobStatusEnum.RUNNING.getCode(),
            current.corpus(),
            null,
            List.of(),
            null,
            identity.id()
        ));
        try {
            ProductionIngestService.IngestResult result = ingest.ingest();
            save(new IngestJobVO(
                jobId,
                IngestJobStatusEnum.SUCCEEDED.getCode(),
                result.corpus(),
                result.chunkCount(),
                result.sources(),
                null,
                identity.id()
            ));
            if (metrics != null) {
                metrics.ingestSucceeded();
            }
        } catch (RuntimeException ex) {
            log.warn("入库任务失败 jobId={}: {}", jobId, ex.toString());
            save(new IngestJobVO(
                jobId,
                IngestJobStatusEnum.FAILED.getCode(),
                current.corpus(),
                null,
                List.of(),
                ex.getMessage(),
                identity.id()
            ));
            if (metrics != null) {
                metrics.ingestFailed();
            }
        }
    }

    private void save(IngestJobVO job) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobId", job.jobId());
        fields.put("status", job.status());
        fields.put("corpus", job.corpus() == null ? "" : job.corpus());
        fields.put("chunkCount", job.chunkCount() == null ? "" : String.valueOf(job.chunkCount()));
        fields.put("sources", jsonMapper.writeValueAsString(
            job.sources() == null ? List.of() : job.sources()));
        fields.put("errorMessage", job.errorMessage() == null ? "" : job.errorMessage());
        fields.put("instanceId", job.instanceId() == null ? "" : job.instanceId());
        String key = jobKey(job.jobId());
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, JOB_TTL);
    }

    private IngestJobVO toView(Map<Object, Object> raw) {
        String sourcesJson = stringVal(raw.get("sources"));
        List<String> sources = List.of();
        if (sourcesJson != null && !sourcesJson.isBlank()) {
            sources = jsonMapper.readValue(sourcesJson, new TypeReference<List<String>>() {});
        }
        String chunkRaw = stringVal(raw.get("chunkCount"));
        Integer chunkCount = (chunkRaw == null || chunkRaw.isBlank()) ? null : Integer.valueOf(chunkRaw);
        String error = stringVal(raw.get("errorMessage"));
        String instance = stringVal(raw.get("instanceId"));
        return new IngestJobVO(
            stringVal(raw.get("jobId")),
            stringVal(raw.get("status")),
            stringVal(raw.get("corpus")),
            chunkCount,
            sources,
            error == null || error.isBlank() ? null : error,
            instance == null || instance.isBlank() ? null : instance
        );
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP);
        } catch (RuntimeException ex) {
            log.debug("入库 consumer group 可能已存在: {}", ex.toString());
        }
    }

    private void ack(RecordId recordId) {
        if (recordId != null) {
            redis.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);
        }
    }

    private void releaseLock(String jobId) {
        try {
            redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), jobId);
        } catch (RuntimeException ex) {
            log.debug("释放入库锁失败: {}", ex.toString());
        }
    }

    private static String jobKey(String jobId) {
        return JOB_KEY_PREFIX + jobId;
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止消费循环。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        worker.shutdownNow();
    }
}
