package com.feike.ai.production.agent.manager;

import com.feike.ai.production.auth.model.ProductionPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolPolicy")
class ToolPolicyTest {

    @Test
    void userShouldNotSeeRebuildIndex() {
        ProductionPrincipal user = new ProductionPrincipal("alice", "tenant-a", Set.of("USER"));
        assertTrue(ToolPolicy.allows(user, "search_kb"));
        assertFalse(ToolPolicy.allows(user, "rebuild_index"));
        assertFalse(ToolPolicy.allowed(user).contains("rebuild_index"));
    }

    @Test
    void adminShouldAllowRebuildIndex() {
        ProductionPrincipal admin = new ProductionPrincipal("admin", "tenant-a", Set.of("ADMIN", "USER"));
        assertTrue(ToolPolicy.allows(admin, "rebuild_index"));
    }
}
