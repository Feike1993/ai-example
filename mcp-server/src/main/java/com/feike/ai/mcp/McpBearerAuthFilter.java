package com.feike.ai.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP Streamable HTTP 端点 Bearer 校验（学习形态共享密钥）。
 * <p>
 * 仅拦截 {@code /mcp} 与其子路径；无/错 token → 401。不引入完整 Spring Security。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class McpBearerAuthFilter extends OncePerRequestFilter {

    private final byte[] expectedTokenBytes;

    /**
     * @param bearerToken 与主应用 {@code MCP_BEARER_TOKEN} 一致；默认 {@code dev-mcp-token}
     */
    public McpBearerAuthFilter(
        @Value("${app.mcp.auth.bearer-token:dev-mcp-token}") String bearerToken
    ) {
        String normalized = bearerToken == null ? "" : bearerToken.trim();
        this.expectedTokenBytes = normalized.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        // 去掉 context-path 后的相对路径；本应用无 context-path，URI 即为 /mcp…
        return !(path.equals("/mcp") || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (expectedTokenBytes.length == 0) {
            // 空密钥视为关闭鉴权（便于临时排障）；生产应始终配置非空
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !matchesBearer(header)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Unauthorized: missing or invalid Bearer token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析 {@code Authorization: Bearer <token>}（Bearer 大小写不敏感）并常量时间比较。
     */
    boolean matchesBearer(String authorizationHeader) {
        String value = authorizationHeader.trim();
        if (value.length() < 7) {
            return false;
        }
        String prefix = value.substring(0, 7);
        if (!"bearer ".equalsIgnoreCase(prefix)) {
            return false;
        }
        byte[] actual = value.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedTokenBytes, actual);
    }
}
