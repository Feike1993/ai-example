package com.feike.ai.production.auth.controller;

import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.service.SecretUnavailableException;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
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
            writeJson(response, ErrorCodeEnum.SECRET_UNAVAILABLE, "密钥不可用（未配置 PRODUCTION_KEK 或无法解密）");
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authFail();
            writeJson(response, ErrorCodeEnum.AUTH_MISSING_TOKEN);
            return;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            authFail();
            writeJson(response, ErrorCodeEnum.AUTH_MISSING_TOKEN);
            return;
        }
        try {
            ProductionPrincipal principal = jwtService.parse(token);
            request.setAttribute(ProductionPrincipal.ATTR, principal);
            MDC.put("tenant", principal.tenantId());
            MDC.put("sub", principal.subject());
            filterChain.doFilter(request, response);
        } catch (SecretUnavailableException ex) {
            writeJson(response, ErrorCodeEnum.SECRET_UNAVAILABLE, ex.getMessage());
        } catch (BusinessException ex) {
            if (ex.getErrorCode().getHttpStatus() == HttpStatus.UNAUTHORIZED) {
                authFail();
            }
            writeJson(response, ex.getErrorCode(), ex.getMessage());
        } catch (ResponseStatusException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) {
                authFail();
            }
            ErrorCodeEnum errorCode = status == 401
                ? ErrorCodeEnum.AUTH_INVALID
                : ErrorCodeEnum.INTERNAL_ERROR;
            writeJson(response, errorCode, ex.getReason() == null ? errorCode.getDefaultMessage() : ex.getReason());
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

    static void writeJson(HttpServletResponse response, ErrorCodeEnum errorCode) throws IOException {
        writeJson(response, errorCode, errorCode.getDefaultMessage());
    }

    /**
     * Filter 不经过 {@code @RestControllerAdvice}，必须自己写出同一份 {@code {code,message}}。
     *
     * @param response  HTTP 响应
     * @param errorCode 错误码
     * @param message   给用户看的中文
     */
    static void writeJson(HttpServletResponse response, ErrorCodeEnum errorCode, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String text = message == null || message.isBlank() ? errorCode.getDefaultMessage() : message;
        response.getWriter().write("{\"code\":\"" + escapeJson(errorCode.getCode())
            + "\",\"message\":\"" + escapeJson(text) + "\"}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
        throw new BusinessException(ErrorCodeEnum.AUTH_INVALID, "未认证");
    }
}
