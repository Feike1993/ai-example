package com.feike.ai.production.observability.service;

import com.feike.ai.production.observability.model.RunTimelineVO;
import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.model.RunSnapshot;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从已有 run 事件日志抽出步骤。跨租户与过期一律 404，不暴露存在性。
 */
public class ProductionRunTimelineService {

    private final RunEventLogDAO eventLog;
    private final JsonMapper jsonMapper;

    /**
     * @param eventLog   事件日志
     * @param jsonMapper 解析事件 JSON
     */
    public ProductionRunTimelineService(RunEventLogDAO eventLog, JsonMapper jsonMapper) {
        this.eventLog = eventLog;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param runId    run
     * @param tenantId 调用方租户
     * @return 时间线
     */
    public RunTimelineVO get(String runId, String tenantId) {
        RunSnapshot snapshot = eventLog.snapshot(runId).orElseThrow(this::missing);
        List<StreamEvent> events = eventLog.replay(runId, -1L);
        String tenant = null;
        String mode = null;
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Object> done = null;
        Map<String, Object> error = null;
        for (StreamEvent event : events) {
            Map<String, Object> body = asMap(event.data());
            if (event.type() == StreamEventTypeEnum.META) {
                tenant = stringVal(body.get("tenant"));
                mode = stringVal(body.get("mode"));
            } else if (event.type() == StreamEventTypeEnum.STEP) {
                steps.add(body);
            } else if (event.type() == StreamEventTypeEnum.DONE) {
                done = body;
            } else if (event.type() == StreamEventTypeEnum.ERROR) {
                error = body;
            }
        }
        if (tenant == null || !tenant.equals(tenantId)) {
            throw missing();
        }
        return new RunTimelineVO(
            runId,
            snapshot.state().name(),
            tenant,
            mode,
            List.copyOf(steps),
            done,
            error
        );
    }

    private BusinessException missing() {
        return new BusinessException(ErrorCodeEnum.RUN_NOT_FOUND);
    }

    private Map<String, Object> asMap(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = jsonMapper.readValue(data, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return copy;
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private static String stringVal(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
