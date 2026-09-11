package com.feike.ai.production.observability.service;

import com.feike.ai.production.observability.model.RunTimelineVO;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.model.RunStateEnum;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ProductionRunTimelineService")
class ProductionRunTimelineServiceTest {

    private final InMemoryRunEventLogDAOImpl eventLog = new InMemoryRunEventLogDAOImpl();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ProductionRunTimelineService service = new ProductionRunTimelineService(eventLog, jsonMapper);

    @Test
    void missingRunShouldLookAbsent() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.get("missing", "tenant-a"));
        assertEquals(ErrorCodeEnum.RUN_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void otherTenantShouldLookAbsent() {
        seed("run-1", "tenant-a");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.get("run-1", "tenant-b"));
        assertEquals(ErrorCodeEnum.RUN_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void ownerShouldSeeSteps() {
        seed("run-1", "tenant-a");
        RunTimelineVO vo = service.get("run-1", "tenant-a");
        assertEquals("DONE", vo.state());
        assertEquals("agent", vo.mode());
        assertEquals(1, vo.steps().size());
        assertEquals("rebuild_index", vo.steps().getFirst().get("toolName"));
        assertEquals(Boolean.TRUE, vo.steps().getFirst().get("denied"));
    }

    private void seed(String runId, String tenant) {
        eventLog.begin(runId);
        eventLog.append(runId, new StreamEvent(0, StreamEventTypeEnum.META,
            jsonMapper.writeValueAsString(Map.of("runId", runId, "tenant", tenant, "mode", "agent"))));
        eventLog.append(runId, new StreamEvent(1, StreamEventTypeEnum.STEP,
            jsonMapper.writeValueAsString(Map.of(
                "index", 0, "toolName", "rebuild_index", "denied", true,
                "assistantText", "", "toolArgs", "{}", "toolResult", "denied"
            ))));
        eventLog.append(runId, new StreamEvent(2, StreamEventTypeEnum.DONE,
            jsonMapper.writeValueAsString(Map.of("persisted", false))));
        eventLog.finish(runId, RunStateEnum.DONE);
    }
}
