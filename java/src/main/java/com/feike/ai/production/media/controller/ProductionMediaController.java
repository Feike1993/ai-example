package com.feike.ai.production.media.controller;

import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.media.model.MediaProbeVO;
import com.feike.ai.production.media.service.ProductionMediaService;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.ratelimit.service.RateLimitExceededException;
import com.feike.ai.production.speech.model.SpeakQuery;
import com.feike.ai.production.speech.model.TranscribeVO;
import com.feike.ai.production.speech.service.ProductionSpeechService;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图文 / 语音 HTTP 入口。探针不调模型；转写与朗读走现有 chat 令牌桶。
 * <p>
 * 身份由 {@link JwtAuthFilter} 放入 request。不落盘、不把 blob 写入审计。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionMediaController {

    private final ProductionMediaService mediaService;
    private final ProductionSpeechService speechService;
    private final ProductionGuardrail guardrail;
    private final RedisTokenBucket bucket;
    private final AuditService audit;
    private final ProductionMetrics metrics;

    /**
     * @param mediaService  探针
     * @param speechService 语音；测试可空
     * @param guardrail     朗读护栏
     * @param bucket        限流；探针不走桶
     * @param audit         审计
     * @param metrics       指标
     */
    public ProductionMediaController(
        ProductionMediaService mediaService,
        ProductionSpeechService speechService,
        ProductionGuardrail guardrail,
        RedisTokenBucket bucket,
        AuditService audit,
        ProductionMetrics metrics
    ) {
        this.mediaService = mediaService;
        this.speechService = speechService;
        this.guardrail = guardrail;
        this.bucket = bucket;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * 只校验 JWT / mime / 大小，不调模型。
     *
     * @param file 上传
     * @param http 身份
     * @return 摘要
     */
    @PostMapping(value = "/media/probe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaProbeVO probe(@RequestPart("file") MultipartFile file, HttpServletRequest http) {
        ProductionPrincipal principal = principal(http);
        MediaProbeVO result = mediaService.probe(file);
        audit(principal, "media.probe", http, 200, result.sha256());
        return result;
    }

    /**
     * 语音转写。走 chat 令牌桶，避免单独打爆网关。
     *
     * @param audio    音频
     * @param http     身份
     * @param response 限流头
     * @return 文本
     */
    @PostMapping(value = "/speech/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscribeVO transcribe(
        @RequestPart("audio") MultipartFile audio,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        requireSpeech();
        TranscribeVO result = speechService.transcribe(audio);
        audit(principal, "speech.transcribe", http, 200, null);
        return result;
    }

    /**
     * 语音合成。空文本 400；违禁词 422；不写入会话。
     *
     * @param query    文本
     * @param http     身份
     * @param response 限流头
     * @return mpeg 音频
     */
    @PostMapping(value = "/speech/speak", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> speak(
        @Valid @RequestBody SpeakQuery query,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        ProductionPrincipal principal = rateLimit(http, response);
        requireSpeech();
        if (guardrail != null && query.text() != null) {
            guardrail.checkInput(query.text());
        }
        byte[] audio = speechService.speak(query.text());
        audit(principal, "speech.speak", http, 200, null);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/mpeg"))
            .body(audio);
    }

    private void requireSpeech() {
        if (speechService == null) {
            throw new BusinessException(ErrorCodeEnum.INTERNAL_ERROR, "语音服务未装配");
        }
    }

    private ProductionPrincipal rateLimit(HttpServletRequest http, HttpServletResponse response) {
        ProductionPrincipal principal = principal(http);
        if (bucket == null) {
            return principal;
        }
        try {
            int remaining = bucket.consume(
                "prod:rl:" + principal.tenantId() + ":" + principal.subject(), null);
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        } catch (RateLimitExceededException ex) {
            if (metrics != null) {
                metrics.rateLimited();
            }
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Remaining", "0");
            throw ex;
        }
        return principal;
    }

    private ProductionPrincipal principal(HttpServletRequest http) {
        if (http == null) {
            return new ProductionPrincipal("anonymous", "default", java.util.Set.of("USER"));
        }
        Object attr = http.getAttribute(ProductionPrincipal.ATTR);
        if (attr instanceof ProductionPrincipal p) {
            return p;
        }
        return new ProductionPrincipal("anonymous", "default", java.util.Set.of("USER"));
    }

    private void audit(
        ProductionPrincipal principal,
        String action,
        HttpServletRequest http,
        int status,
        String sha256
    ) {
        if (audit == null) {
            return;
        }
        String ip = http == null || http.getRemoteAddr() == null ? null : http.getRemoteAddr();
        audit.record(
            principal.tenantId(), principal.subject(), action,
            http == null ? "" : http.getServletPath(),
            status, null, sha256, null, ip
        );
    }
}
