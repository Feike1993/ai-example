package com.feike.ai.production.agent.manager;

import com.feike.ai.production.auth.model.ProductionPrincipal;

import java.util.Locale;
import java.util.Set;

/**
 * 按角色决定模型能看见哪些工具。
 * <p>
 * 被拒的工具不会出现在发给模型的 schema 里——如果告诉模型有一个它调不了的工具，
 * 它几乎一定会去调，然后失败。拒绝发生在「看见」之前，而不是执行之后。
 */
public final class ToolPolicy {

    private static final Set<String> USER_TOOLS = Set.of("search_kb", "add", "get_weather");
    private static final Set<String> ADMIN_EXTRA = Set.of("rebuild_index");

    private ToolPolicy() {}

    /**
     * @param principal 调用方
     * @return 允许的工具名
     */
    public static Set<String> allowed(ProductionPrincipal principal) {
        if (principal != null && principal.admin()) {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(USER_TOOLS);
            all.addAll(ADMIN_EXTRA);
            return Set.copyOf(all);
        }
        return USER_TOOLS;
    }

    /**
     * @param principal 调用方
     * @param toolName  工具名
     * @return 是否允许执行
     */
    public static boolean allows(ProductionPrincipal principal, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return allowed(principal).contains(toolName.trim().toLowerCase(Locale.ROOT))
            || allowed(principal).contains(toolName.trim());
    }
}
