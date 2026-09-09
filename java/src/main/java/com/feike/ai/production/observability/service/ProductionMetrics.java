package com.feike.ai.production.observability.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工业级业务指标。浏览器不直连 Prometheus，由 {@code /api/v1/ops/snapshot} 读一份安全子集。
 */
public class ProductionMetrics {

    private final Counter chatRuns;
    private final Counter agentRuns;
    private final Counter retrievalEmpty;
    private final Counter guardrailBlocked;
    private final Counter toolDenied;
    private final Counter rateLimited;
    private final Counter authFail;
    private final Timer chatTimer;
    private final MeterRegistry registry;

    /**
     * @param registry Micrometer
     */
    public ProductionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.chatRuns = Counter.builder("prod.chat.runs").description("生产问答次数").register(registry);
        this.agentRuns = Counter.builder("prod.agent.runs").description("生产 Agent 次数").register(registry);
        this.retrievalEmpty = Counter.builder("prod.retrieval.empty").description("空检索").register(registry);
        this.guardrailBlocked = Counter.builder("prod.guardrail.blocked").description("护栏拦截").register(registry);
        this.toolDenied = Counter.builder("prod.tool.denied").description("工具策略拒绝").register(registry);
        this.rateLimited = Counter.builder("prod.rate_limited").description("限流").register(registry);
        this.authFail = Counter.builder("prod.auth.fail").description("鉴权失败").register(registry);
        this.chatTimer = Timer.builder("prod.chat.duration").description("问答耗时").register(registry);
    }

    /** 问答计数。 */
    public void chatRun() {
        chatRuns.increment();
    }

    /** Agent 计数。 */
    public void agentRun() {
        agentRuns.increment();
    }

    /** 空检索。 */
    public void emptyRetrieval() {
        retrievalEmpty.increment();
    }

    /** 护栏。 */
    public void guardrail() {
        guardrailBlocked.increment();
    }

    /** 工具拒绝。 */
    public void toolDenied() {
        toolDenied.increment();
    }

    /** 限流。 */
    public void rateLimited() {
        rateLimited.increment();
    }

    /** 鉴权失败。 */
    public void authFail() {
        authFail.increment();
    }

    /**
     * @param nanos 耗时
     */
    public void recordDuration(long nanos) {
        chatTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * @return 给前端的快照
     */
    public Map<String, Number> snapshot() {
        Map<String, Number> map = new LinkedHashMap<>();
        map.put("chatRuns", chatRuns.count());
        map.put("agentRuns", agentRuns.count());
        map.put("retrievalEmpty", retrievalEmpty.count());
        map.put("guardrailBlocked", guardrailBlocked.count());
        map.put("toolDenied", toolDenied.count());
        map.put("rateLimited", rateLimited.count());
        map.put("authFail", authFail.count());
        map.put("chatDurationCount", chatTimer.count());
        map.put("meters", registry.getMeters().size());
        return map;
    }
}
