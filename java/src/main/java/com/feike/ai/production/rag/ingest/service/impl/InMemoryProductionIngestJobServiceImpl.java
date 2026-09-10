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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内异步入库：给单测和 {@code event-log=memory} 用。
 * <p>
 * 生产双实例必须走 Redis 实现，否则两台会各建各的索引。
 */
public class InMemoryProductionIngestJobServiceImpl implements ProductionIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProductionIngestJobServiceImpl.class);

    private final ProductionIngestService ingest;
    private final ProductionProperties properties;
    private final ProductionInstanceIdentity identity;
    private final ProductionMetrics metrics;
    private final ConcurrentHashMap<String, IngestJobVO> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestId = new AtomicReference<>();
    private final AtomicReference<String> activeId = new AtomicReference<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param ingest     实际建索引
     * @param properties corpus
     * @param identity   实例短名
     * @param metrics    可空
     */
    public InMemoryProductionIngestJobServiceImpl(
        ProductionIngestService ingest,
        ProductionProperties properties,
        ProductionInstanceIdentity identity,
        ProductionMetrics metrics
    ) {
        this.ingest = ingest;
        this.properties = properties;
        this.identity = identity;
        this.metrics = metrics;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IngestJobVO submit() {
        String existing = activeId.get();
        if (existing != null) {
            IngestJobVO current = jobs.get(existing);
            if (current != null && !IngestJobStatusEnum.from(current.status()).terminal()) {
                return current;
            }
        }
        String jobId = UUID.randomUUID().toString();
        IngestJobVO queued = new IngestJobVO(
            jobId,
            IngestJobStatusEnum.QUEUED.getCode(),
            properties.corpus(),
            null,
            List.of(),
            null,
            null
        );
        jobs.put(jobId, queued);
        latestId.set(jobId);
        if (!activeId.compareAndSet(null, jobId) && !activeId.compareAndSet(existing, jobId)) {
            IngestJobVO raced = jobs.get(activeId.get());
            if (raced != null && !IngestJobStatusEnum.from(raced.status()).terminal()) {
                jobs.remove(jobId);
                return raced;
            }
        }
        if (metrics != null) {
            metrics.ingestSubmitted();
        }
        workers.execute(() -> runJob(jobId));
        return queued;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IngestJobVO get(String jobId) {
        IngestJobVO job = jobs.get(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCodeEnum.INGEST_JOB_NOT_FOUND);
        }
        return job;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<IngestJobVO> getLatest() {
        String id = latestId.get();
        return id == null ? Optional.empty() : Optional.ofNullable(jobs.get(id));
    }

    private void runJob(String jobId) {
        jobs.computeIfPresent(jobId, (id, current) -> new IngestJobVO(
            id,
            IngestJobStatusEnum.RUNNING.getCode(),
            current.corpus(),
            null,
            List.of(),
            null,
            identity.id()
        ));
        try {
            ProductionIngestService.IngestResult result = ingest.ingest();
            jobs.put(jobId, new IngestJobVO(
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
            jobs.put(jobId, new IngestJobVO(
                jobId,
                IngestJobStatusEnum.FAILED.getCode(),
                properties.corpus(),
                null,
                List.of(),
                ex.getMessage(),
                identity.id()
            ));
            if (metrics != null) {
                metrics.ingestFailed();
            }
        } finally {
            activeId.compareAndSet(jobId, null);
        }
    }

    /**
     * 关闭工作线程。
     */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
