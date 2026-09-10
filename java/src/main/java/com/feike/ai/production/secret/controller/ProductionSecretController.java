package com.feike.ai.production.secret.controller;

import com.feike.ai.production.auth.controller.JwtAuthFilter;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.model.SecretRotateVO;
import com.feike.ai.production.web.BusinessException;
import com.feike.ai.production.web.ErrorCodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地 KEK 轮换：用当前 {@code PRODUCTION_KEK} 重加密 {@code prod_secret}。
 * <p>
 * 轮换窗口内进程同时持有 {@code PRODUCTION_KEK} 与 {@code PRODUCTION_KEK_PREVIOUS}，
 * 旧密文仍可读。云 KMS / 定时自动轮换不做。
 */
@RestController
@RequestMapping("/api/v1/secrets")
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionSecretController {

    private final SecretResolver secrets;
    private final ProductionProperties properties;

    /**
     * @param secrets    密钥存储
     * @param properties 当前 kek-id
     */
    public ProductionSecretController(SecretResolver secrets, ProductionProperties properties) {
        this.secrets = secrets;
        this.properties = properties;
    }

    /**
     * 用当前 KEK 重加密全部密文行。仅 ADMIN。
     *
     * @param http 身份
     * @return 重写行数与新 kek-id
     */
    @PostMapping("/rotate")
    public SecretRotateVO rotate(HttpServletRequest http) {
        ProductionPrincipal principal = JwtAuthFilter.require(http);
        if (!principal.admin()) {
            throw new BusinessException(ErrorCodeEnum.FORBIDDEN);
        }
        int rewritten = secrets.reencryptAll();
        return new SecretRotateVO(rewritten, properties.security().kekId());
    }
}
