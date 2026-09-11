package com.feike.ai.production.observability.model;

import java.util.List;
import java.util.Map;

/**
 * 从 SSE 事件日志重建的 run 时间线。不另存一份步骤。
 *
 * @param runId  run
 * @param state  快照状态
 * @param tenant meta 里的租户
 * @param mode   meta.mode，问答可空
 * @param steps  step 事件负载
 * @param done   done 负载；可空
 * @param error  error 负载；可空
 */
public record RunTimelineVO(
    String runId,
    String state,
    String tenant,
    String mode,
    List<Map<String, Object>> steps,
    Map<String, Object> done,
    Map<String, Object> error
) {}
