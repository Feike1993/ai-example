package com.feike.ai.production.web;

import com.feike.ai.production.config.ProductionInstanceIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给 {@code /api/v1} 响应打上实例短名，多实例演示时对照「这一枪打到了哪台」。
 */
public class InstanceIdFilter extends OncePerRequestFilter {

    /** 响应头名，与 Nginx 反代透传。 */
    public static final String HEADER = "X-Instance-Id";

    private final ProductionInstanceIdentity identity;

    /**
     * @param identity 本进程短名
     */
    public InstanceIdFilter(ProductionInstanceIdentity identity) {
        this.identity = identity;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(HEADER, identity.id());
        filterChain.doFilter(request, response);
    }
}
