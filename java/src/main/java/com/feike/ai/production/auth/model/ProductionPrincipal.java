package com.feike.ai.production.auth.model;

import java.util.Set;

/**
 * 已认证的工业级调用方。
 * <p>
 * SSE 跑在虚拟线程上，不能靠 {@code SecurityContextHolder} 跨线程取身份，
 * 所以控制器在请求线程里取出本对象，作为显式参数传到编排层。
 *
 * @param subject  用户名（JWT sub）
 * @param tenantId 租户
 * @param roles    角色集合，大写
 */
public record ProductionPrincipal(String subject, String tenantId, Set<String> roles) {

    /** 放在 HttpServletRequest 上的属性名。 */
    public static final String ATTR = "prod.principal";

    /**
     * @param role 角色名
     * @return 是否拥有
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * @return 是否管理员
     */
    public boolean admin() {
        return hasRole("ADMIN");
    }
}
