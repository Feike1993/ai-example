package com.feike.ai.production.rag.ingest.service.impl;

import com.feike.ai.production.config.ProductionInstanceIdentity;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.ingest.model.IngestJobStatusEnum;
import com.feike.ai.production.rag.ingest.model.IngestJobVO;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.web.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("InMemoryProductionIngestJobService")
class InMemoryProductionIngestJobServiceImplTest {

    @Test
    void submitShouldRunIngestAndMarkSucceeded() throws Exception {
        ProductionIngestService ingest = mock(ProductionIngestService.class);
        CountDownLatch done = new CountDownLatch(1);
        when(ingest.ingest()).thenAnswer(invocation -> {
            done.countDown();
            return new ProductionIngestService.IngestResult("prod-corpus", 3, List.of("a.md"));
        });
        InMemoryProductionIngestJobServiceImpl jobs = service(ingest);

        IngestJobVO submitted = jobs.submit();
        assertEquals(IngestJobStatusEnum.QUEUED.getCode(), submitted.status());
        assertTrue(done.await(3, TimeUnit.SECONDS));
        IngestJobVO finished = waitSucceeded(jobs, submitted.jobId());
        assertEquals(3, finished.chunkCount());
        assertEquals(List.of("a.md"), finished.sources());
        jobs.shutdown();
    }

    @Test
    void unknownJobShould404() {
        InMemoryProductionIngestJobServiceImpl jobs = service(mock(ProductionIngestService.class));
        assertThrows(BusinessException.class, () -> jobs.get("missing"));
        jobs.shutdown();
    }

    private static IngestJobVO waitSucceeded(InMemoryProductionIngestJobServiceImpl jobs, String jobId)
        throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            IngestJobVO job = jobs.get(jobId);
            if (IngestJobStatusEnum.SUCCEEDED.getCode().equals(job.status())) {
                return job;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("入库未在超时前完成: " + jobs.get(jobId));
    }

    private static InMemoryProductionIngestJobServiceImpl service(ProductionIngestService ingest) {
        return new InMemoryProductionIngestJobServiceImpl(
            ingest,
            new ProductionProperties(true, "prod-corpus", 4, 400, 1, true, 60, 4, null, null, null, null, null, null, null),
            new ProductionInstanceIdentity("test"),
            new ProductionMetrics(new SimpleMeterRegistry())
        );
    }
}
