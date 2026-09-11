package com.feike.ai.production.auth.controller;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * standalone MockMvc 用：把固定主体放进 request，替代完整 JWT 过滤器。
 */
public final class TestProductionPrincipalFilter extends OncePerRequestFilter {

    /** SSE 契约测试默认身份。 */
    public static final ProductionPrincipal TESTER =
        new ProductionPrincipal("tester", "default", Set.of("USER"));

    private final ProductionPrincipal principal;

    /**
     * 使用 {@link #TESTER}。
     */
    public TestProductionPrincipalFilter() {
        this(TESTER);
    }

    /**
     * @param principal 放入 request 的主体
     */
    public TestProductionPrincipalFilter(ProductionPrincipal principal) {
        this.principal = principal;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        request.setAttribute(ProductionPrincipal.ATTR, principal);
        filterChain.doFilter(request, response);
    }
}
