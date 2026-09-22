package com.feike.ai.production.modelsettings.config;

import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.modelsettings.service.GlobalModelSettingsService;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.secret.dao.impl.JdbcSecretDAOImpl;
import com.feike.ai.production.secret.manager.EnvSecretResolver;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.secret.manager.SecretBootstrap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 教学场和工业场共用的模型设置基础设施。
 * <p>
 * 不受 {@code app.production.enabled} 控制：所有模型调用统一读取数据库 Provider、
 * 能力路由与加密密钥。
 */
@Configuration(proxyBeanMethods = false)
public class ModelSettingsConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "app.production.security", name = "secret-store", havingValue = "postgres", matchIfMissing = true)
    public SecretResolver jdbcSecretStore(
        JdbcTemplate jdbc,
        ProductionProperties properties,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcSecretDAOImpl(jdbc, properties, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.production.security", name = "secret-store", havingValue = "env")
    public SecretResolver envSecretStore() {
        return new EnvSecretResolver();
    }

    @Bean
    public SecretBootstrap secretBootstrap(SecretResolver secrets) {
        return new SecretBootstrap(secrets);
    }

    @Bean
    public GlobalModelSettingsService globalModelSettingsService(
        JdbcTemplate jdbc,
        SecretResolver secrets
    ) {
        return new GlobalModelSettingsService(jdbc, secrets);
    }

    @Bean
    public ProductionModelFactory productionModelFactory(
        SecretResolver secrets,
        GlobalModelSettingsService settings
    ) {
        return new ProductionModelFactory(secrets, settings);
    }
}
