package com.feike.ai.production.observability.controller;

import com.feike.ai.production.observability.model.RunTimelineVO;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.observability.service.ProductionRunTimelineService;
import com.feike.ai.production.rag.ingest.service.ProductionIngestJobService;

import com.feike.ai.production.audit.model.AuditEntryDO;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ProductionIngestJobService ingestJobs;
    private final JsonMapper jsonMapper;
    private final String loadtestSummaryPath;
    private final ProductionRunTimelineService timelines;

    /**
     * @param audit                审计
     * @param metrics              指标
     * @param tracer               可空
     * @param ingestJobs           最近入库任务；可空
     * @param jsonMapper           读压测摘要
     * @param loadtestSummaryPath  摘要文件；空则按常见相对路径探测
     * @param eventLog             run 事件日志；可空
     */
    public ProductionOpsController(
        AuditService audit,
        ProductionMetrics metrics,
        ObjectProvider<Tracer> tracer,
        ObjectProvider<ProductionIngestJobService> ingestJobs,
        JsonMapper jsonMapper,
        @Value("${PRODUCTION_LOADTEST_SUMMARY:}") String loadtestSummaryPath,
        ObjectProvider<RunEventLogDAO> eventLog
    ) {
        this.audit = audit;
        this.metrics = metrics;
        this.tracer = tracer;
        this.ingestJobs = ingestJobs == null ? null : ingestJobs.getIfAvailable();
        this.jsonMapper = jsonMapper;
        this.loadtestSummaryPath = loadtestSummaryPath;
        RunEventLogDAO log = eventLog == null ? null : eventLog.getIfAvailable();
        this.timelines = log == null ? null : new ProductionRunTimelineService(log, jsonMapper);
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
        if (ingestJobs != null) {
            ingestJobs.getLatest().ifPresent(job -> {
                body.put("ingestJobId", job.jobId());
                body.put("ingestStatus", job.status());
                body.put("ingestChunkCount", job.chunkCount());
                body.put("ingestError", job.errorMessage());
            });
        }
        return body;
    }

    /**
     * 最近一次本机 k6 摘要。文件不存在时 404，不是服务故障。
     *
     * @param http 身份
     * @return k6 summary-export JSON
     */
    @GetMapping("/ops/loadtest")
    public Map<String, Object> loadtest(HttpServletRequest http) {
        JwtAuthFilter.require(http);
        Path file = resolveLoadtestSummary();
        if (file == null || !Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCodeEnum.LOADTEST_SUMMARY_MISSING);
        }
        try {
            return jsonMapper.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {});
        } catch (IOException ex) {
            throw new BusinessException(ErrorCodeEnum.LOADTEST_SUMMARY_MISSING, "压测摘要无法读取");
        }
    }

    /**
     * 从 SSE 事件日志重建步骤时间线。过期或跨租户返回 404。
     *
     * @param runId run
     * @param http  身份
     * @return 时间线
     */
    @GetMapping("/ops/runs/{runId}")
    public RunTimelineVO runTimeline(@PathVariable String runId, HttpServletRequest http) {
        ProductionPrincipal principal = JwtAuthFilter.require(http);
        if (timelines == null) {
            throw new BusinessException(ErrorCodeEnum.RUN_NOT_FOUND);
        }
        return timelines.get(runId, principal.tenantId());
    }

    private Path resolveLoadtestSummary() {
        if (loadtestSummaryPath != null && !loadtestSummaryPath.isBlank()) {
            return Path.of(loadtestSummaryPath).toAbsolutePath().normalize();
        }
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        Path nested = cwd.resolve("loadtest/results/latest-summary.json").normalize();
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        return cwd.resolve("../loadtest/results/latest-summary.json").normalize();
    }
}
