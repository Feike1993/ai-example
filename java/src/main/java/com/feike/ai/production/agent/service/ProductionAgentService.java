package com.feike.ai.production.agent.service;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.sse.service.SseStreamWriter;

import java.util.List;

/**
 * 工业级 Agent 业务门面。
 */
public interface ProductionAgentService {

    /**
     * 同步 Agent 结果。
     *
     * @param sessionId       会话
     * @param answer          终答
     * @param steps           工具步骤
     * @param reachedMaxSteps 是否触达上限
     * @param persisted       是否入库
     */
    record AgentAnswer(
        String sessionId,
        String answer,
        List<ProductionAgentLoop.Step> steps,
        boolean reachedMaxSteps,
        boolean persisted
    ) {}

    /**
     * 同步运行。
     */
    AgentAnswer run(
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    );

    /**
     * 流式运行。
     */
    void stream(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    );
}
