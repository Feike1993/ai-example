package com.feike.ai.production.observability.controller;

import com.feike.ai.production.observability.service.ProductionMetrics;

import com.feike.ai.production.audit.model.AuditEntryDO;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全面板与可观测面板的只读接口。不暴露原始 Prometheus 刮取。
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionOpsController {

    private final AuditService audit;
    private final ProductionMetrics metrics;
    private final ObjectProvider<Tracer> tracer;

    /**
     * @param audit   审计
     * @param metrics 指标
     * @param tracer  可空
     */
    public ProductionOpsController(
        AuditService audit,
        ProductionMetrics metrics,
        ObjectProvider<Tracer> tracer
    ) {
        this.audit = audit;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * 本租户最近审计。
     *
     * @param http  身份
     * @param limit 条数
     * @return 记录
     */
    @GetMapping("/audit")
    public List<AuditEntryDO> audit(
        HttpServletRequest http,
        @RequestParam(defaultValue = "50") int limit
    ) {
        ProductionPrincipal principal = JwtAuthFilter.require(http);
        return audit.recent(principal.tenantId(), limit);
    }

    /**
     * 指标快照 + 当前 traceId。
     *
     * @param http 身份
     * @return 快照
     */
    @GetMapping("/ops/snapshot")
    public Map<String, Object> snapshot(HttpServletRequest http) {
        JwtAuthFilter.require(http);
        Map<String, Object> body = new LinkedHashMap<>(metrics.snapshot());
        Tracer current = tracer.getIfAvailable();
        String traceId = null;
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null) {
                traceId = span.context().traceId();
            }
        }
        body.put("traceId", traceId);
        return body;
    }
}
