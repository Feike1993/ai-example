package com.feike.ai.production.config;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.production.chat.service.ProductionChatService;
import com.feike.ai.production.lock.manager.InMemorySessionLock;
import com.feike.ai.production.lock.manager.RedisSessionLock;
import com.feike.ai.production.lock.manager.SessionLock;
import com.feike.ai.production.session.dao.impl.JdbcProductionChatSessionDAOImpl;
import com.feike.ai.production.session.dao.ProductionChatSessionDAO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 条件装配：开关组合对不对，只有装配阶段能验证。
 * <p>
 * 这类问题全都在启动那一刻才暴露——而那正是最不该发现问题的时候。用
 * {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：它只加载这一个配置类，
 * 不需要真的连上 pgvector 或 Redis，几毫秒就能把开关矩阵跑完。
 */
@DisplayName("ProductionConfiguration 条件装配")
class ProductionConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
            PropertyPlaceholderAutoConfiguration.class))
        .withUserConfiguration(StubCollaborators.class, PropertiesHolder.class, ProductionConfiguration.class)
        .withPropertyValues(
            "app.production.enabled=true",
            "app.production.stream.event-log=memory",
            "app.production.security.secret-store=env"
        );

    @Test
    void disabledProductionShouldRegisterNothing() {
        runner.withPropertyValues("app.production.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ProductionChatService.class);
            assertThat(context).doesNotHaveBean(ProductionChatSessionDAO.class);
            assertThat(context).doesNotHaveBean(SessionLock.class);
        });
    }

    @Test
    void sessionEnabledShouldWireJdbcStore() {
        runner.withPropertyValues(
            "app.production.session.enabled=true",
            "app.production.session.lock=memory"
        ).run(context -> {
            assertThat(context).hasSingleBean(ProductionChatSessionDAO.class);
            assertThat(context.getBean(ProductionChatSessionDAO.class))
                .isInstanceOf(JdbcProductionChatSessionDAOImpl.class);
            assertThat(context.getBean(ProductionChatService.class).sessionEnabled()).isTrue();
        });
    }

    @Test
    void sessionDisabledShouldStillBuildChatServiceWithoutStore() {
        runner.withPropertyValues("app.production.session.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ProductionChatSessionDAO.class);
            // 会话关掉不能把整条问答链路带下水
            assertThat(context).hasSingleBean(ProductionChatService.class);
            assertThat(context.getBean(ProductionChatService.class).sessionEnabled()).isFalse();
        });
    }

    @Test
    void lockShouldDefaultToRedisAndSwitchToMemoryOnDemand() {
        runner.run(context ->
            assertThat(context.getBean(SessionLock.class)).isInstanceOf(RedisSessionLock.class));

        runner.withPropertyValues("app.production.session.lock=memory").run(context ->
            assertThat(context.getBean(SessionLock.class)).isInstanceOf(InMemorySessionLock.class));
    }

    /** 只提供装配所需的协作者，全部是替身：这里验证的是条件，不是行为。 */
    @Configuration(proxyBeanMethods = false)
    static class StubCollaborators {

        @Bean
        AiProperties aiProperties() {
            return new AiProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProductionProperties.class)
    static class PropertiesHolder {
    }
}
