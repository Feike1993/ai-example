package com.feike.ai.production.agent.service;

import com.feike.ai.production.agent.model.AgentTurnMedia;
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
     * @param runId           本次 run；同步路径也会生成，便于审计对齐
     */
    record AgentAnswer(
        String sessionId,
        String answer,
        List<ProductionAgentLoop.Step> steps,
        boolean reachedMaxSteps,
        boolean persisted,
        String runId
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
     * 流式运行（纯文本）。
     */
    default void stream(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider
    ) {
        stream(writer, principal, sessionId, question, provider, AgentTurnMedia.none());
    }

    /**
     * 流式运行。有图时调用方应已完成 VL 转写，本方法只把正文拼进用户任务。
     *
     * @param media 本轮文档抽出与图片转写；无附件传 {@link AgentTurnMedia#none()}
     */
    void stream(
        SseStreamWriter writer,
        ProductionPrincipal principal,
        String sessionId,
        String question,
        String provider,
        AgentTurnMedia media
    );
}
