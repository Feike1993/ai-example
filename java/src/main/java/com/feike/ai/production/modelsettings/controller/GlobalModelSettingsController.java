package com.feike.ai.production.modelsettings.controller;

import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.modelsettings.model.*;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 全局管理员的模型与服务设置入口。 */
@RestController
@RequestMapping("/api/v1/model-settings")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class GlobalModelSettingsController {
    private final GlobalModelSettingsService settings;
    private final AuditService audit;
    private final ProductionModelFactory models;
    public GlobalModelSettingsController(GlobalModelSettingsService settings, AuditService audit, ProductionModelFactory models) { this.settings = settings; this.audit = audit; this.models = models; }
    @GetMapping public ModelSettingsVO list(HttpServletRequest http) { requireAdmin(http); return settings.list(); }
    @PutMapping("/providers/{id}") public void saveProvider(@PathVariable String id, @RequestBody ProviderUpsertRequest body, HttpServletRequest http) {
        ProductionPrincipal p = requireAdmin(http); settings.saveProvider(id, body); models.invalidate(); audit.record(p.tenantId(), p.subject(), "model.provider.save", "/api/v1/model-settings/providers/" + id, 204, null, null, null, null);
    }
    @PutMapping("/routes/{capability}") public void saveRoute(@PathVariable String capability, @RequestBody RouteUpsertRequest body, HttpServletRequest http) {
        ProductionPrincipal p = requireAdmin(http); settings.saveRoute(capability, body); models.invalidate(); audit.record(p.tenantId(), p.subject(), "model.route.save", "/api/v1/model-settings/routes/" + capability, 204, null, null, null, null);
    }
    private static ProductionPrincipal requireAdmin(HttpServletRequest http) {
        ProductionPrincipal p = JwtAuthFilter.require(http);
        if (!p.admin()) {
            throw new BusinessException(ErrorCodeEnum.FORBIDDEN, "禁止访问，只有admin 用户有访问权限");
        }
        return p;
    }
}
