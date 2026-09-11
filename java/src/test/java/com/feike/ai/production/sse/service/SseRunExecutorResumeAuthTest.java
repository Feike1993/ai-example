package com.feike.ai.production.sse.service;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 续传必须绑租户，跨租户与过期一样返回 410，不暴露存在性。
 */
@DisplayName("SseRunExecutor resume auth")
class SseRunExecutorResumeAuthTest {

    private final InMemoryRunEventLogDAOImpl eventLog = new InMemoryRunEventLogDAOImpl();
    private final SseRunExecutor executor = new SseRunExecutor(
        eventLog, JsonMapper.builder().build(), properties());

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void resumeShouldRejectForeignTenant() {
        eventLog.begin("run-a", "tenant-a");
        eventLog.append("run-a", new StreamEvent(0, StreamEventTypeEnum.META, "{\"tenant\":\"tenant-a\"}"));
        eventLog.append("run-a", new StreamEvent(1, StreamEventTypeEnum.DONE, "{}"));
        eventLog.finish("run-a", RunStateEnum.DONE);

        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> executor.resume("run-a", -1, "tenant-b")
        );
        assertEquals(ErrorCodeEnum.RUN_GONE, ex.getErrorCode());
        assertDoesNotThrow(() -> executor.resume("run-a", -1, "tenant-a"));
    }

    @Test
    void resumeWithoutTenantShouldLookGone() {
        eventLog.begin("run-b", "tenant-a");
        BusinessException ex = assertThrows(
            BusinessException.class,
            () -> executor.resume("run-b", -1, null)
        );
        assertEquals(ErrorCodeEnum.RUN_GONE, ex.getErrorCode());
    }

    private static ProductionProperties properties() {
        return new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            new ProductionProperties.Stream("memory", Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofSeconds(10)),
            new ProductionProperties.Session(true, "memory", 20, 2000, Duration.ofMinutes(1), 3),
            null, null, null, null, null, null
        );
    }
}
