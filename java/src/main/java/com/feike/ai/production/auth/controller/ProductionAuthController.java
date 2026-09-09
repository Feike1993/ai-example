package com.feike.ai.production.auth.controller;

import com.feike.ai.production.auth.manager.DemoUserService;
import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.auth.model.LoginRequestDTO;
import com.feike.ai.production.auth.model.MeVO;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.auth.model.TokenVO;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.ratelimit.service.RateLimitExceededException;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录与当前身份。{@code /auth/token} 匿名可访问，但按 IP 限流。
 */
@Validated
@RestController
@ResponseBody
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionAuthController {

    private final DemoUserService users;
    private final JwtService jwtService;
    private final RedisTokenBucket bucket;
    private final ProductionProperties properties;
    private final AuditService audit;
    private final ProductionMetrics metrics;

    /**
     * @param users      演示账号
     * @param jwtService 令牌
     * @param bucket     限流
     * @param properties 登录桶容量
     * @param audit      审计
     * @param metrics    指标
     */
    public ProductionAuthController(
        DemoUserService users,
        JwtService jwtService,
        RedisTokenBucket bucket,
        ProductionProperties properties,
        AuditService audit,
        ProductionMetrics metrics
    ) {
        this.users = users;
        this.jwtService = jwtService;
        this.bucket = bucket;
        this.properties = properties;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * 换取 JWT。
     *
     * @param request  用户名密码
     * @param http     取 IP
     * @param response 限流头
     * @return 令牌与主体
     */
    @PostMapping("/auth/token")
    public TokenVO token(
        @Valid @RequestBody LoginRequestDTO request,
        HttpServletRequest http,
        HttpServletResponse response
    ) {
        String ip = clientIp(http);
        try {
            int remaining = bucket.consume("prod:rl:login:" + ip, properties.rateLimit().loginCapacity());
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        } catch (RateLimitExceededException ex) {
            metrics.rateLimited();
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Remaining", "0");
            throw ex;
        }
        try {
            ProductionPrincipal principal = users.authenticate(request.username(), request.password());
            String token = jwtService.issue(principal);
            audit.record(
                principal.tenantId(), principal.subject(), "auth.login",
                "/api/v1/auth/token", 200, null, null, null, ip
            );
            return new TokenVO(
                token,
                principal.subject(),
                principal.tenantId(),
                List.copyOf(principal.roles()),
                properties.security().jwtTtl().toSeconds()
            );
        } catch (RuntimeException ex) {
            metrics.authFail();
            throw ex;
        }
    }

    /**
     * 当前令牌对应的主体。
     *
     * @param http 已通过过滤器
     * @return 身份
     */
    @GetMapping("/me")
    public MeVO me(HttpServletRequest http) {
        ProductionPrincipal principal = JwtAuthFilter.require(http);
        return new MeVO(principal.subject(), principal.tenantId(), List.copyOf(principal.roles()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
