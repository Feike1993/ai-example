package com.feike.ai.production.auth.controller;

import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.secret.dao.SecretResolver;

import com.feike.ai.production.secret.service.SecretUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 只武装 {@code /api/v1/**}：缺密钥 503，缺/坏令牌 401，登录接口放行。
 * <p>
 * 校验成功后把 {@link ProductionPrincipal} 放进 request attribute，供控制器显式下传，
 * 避免 SSE 虚拟线程丢掉 SecurityContext。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SecretResolver secrets;
    private final ProductionMetrics metrics;

    /**
     * @param jwtService 令牌
     * @param secrets    用于判定信封是否可用
     * @param metrics    鉴权失败计数；可空
     */
    public JwtAuthFilter(
        JwtService jwtService,
        SecretResolver secrets,
        ProductionMetrics metrics
    ) {
        this.jwtService = jwtService;
        this.secrets = secrets;
        this.metrics = metrics;
    }

    /**
     * 单测用：不计指标。
     *
     * @param jwtService 令牌
     * @param secrets    信封
     */
    public JwtAuthFilter(JwtService jwtService, SecretResolver secrets) {
        this(jwtService, secrets, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if (path != null && path.contains("/actuator/prometheus")) {
            return false;
        }
        if (path == null || !path.contains("/api/v1")) {
            return true;
        }
        return path.endsWith("/api/v1/auth/token") && "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!secrets.available()) {
            writeJson(response, 503, "密钥不可用（未配置 PRODUCTION_KEK 或无法解密）");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authFail();
            writeJson(response, 401, "缺少 Authorization: Bearer 令牌");
            return;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            authFail();
            writeJson(response, 401, "缺少 Authorization: Bearer 令牌");
            return;
        }
        try {
            ProductionPrincipal principal = jwtService.parse(token);
            request.setAttribute(ProductionPrincipal.ATTR, principal);
            MDC.put("tenant", principal.tenantId());
            MDC.put("sub", principal.subject());
            filterChain.doFilter(request, response);
        } catch (SecretUnavailableException ex) {
            writeJson(response, 503, ex.getMessage());
        } catch (ResponseStatusException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) {
                authFail();
            }
            writeJson(response, status, ex.getReason() == null ? "未认证" : ex.getReason());
        } finally {
            MDC.remove("tenant");
            MDC.remove("sub");
        }
    }

    private void authFail() {
        if (metrics != null) {
            metrics.authFail();
        }
    }

    static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"error\":\"" + escaped + "\"}");
    }

    /**
     * 从已过滤的请求取出主体。
     *
     * @param request HTTP 请求
     * @return 主体
     */
    public static ProductionPrincipal require(HttpServletRequest request) {
        Object value = request.getAttribute(ProductionPrincipal.ATTR);
        if (value instanceof ProductionPrincipal principal) {
            return principal;
        }
        throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未认证");
    }
}
