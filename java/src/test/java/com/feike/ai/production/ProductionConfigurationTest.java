package com.feike.ai.production;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.production.chat.ProductionChatService;
import com.feike.ai.production.lock.InMemorySessionLock;
import com.feike.ai.production.lock.RedisSessionLock;
import com.feike.ai.production.lock.SessionLock;
import com.feike.ai.production.session.JdbcProductionChatSessionStore;
import com.feike.ai.production.session.ProductionChatSessionStore;
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
            "app.production.stream.event-log=memory"
        );

    @Test
    void disabledProductionShouldRegisterNothing() {
        runner.withPropertyValues("app.production.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ProductionChatService.class);
            assertThat(context).doesNotHaveBean(ProductionChatSessionStore.class);
            assertThat(context).doesNotHaveBean(SessionLock.class);
        });
    }

    @Test
    void sessionEnabledShouldWireJdbcStore() {
        runner.withPropertyValues(
            "app.production.session.enabled=true",
            "app.production.session.lock=memory"
        ).run(context -> {
            assertThat(context).hasSingleBean(ProductionChatSessionStore.class);
            assertThat(context.getBean(ProductionChatSessionStore.class))
                .isInstanceOf(JdbcProductionChatSessionStore.class);
            assertThat(context.getBean(ProductionChatService.class).sessionEnabled()).isTrue();
        });
    }

    @Test
    void sessionDisabledShouldStillBuildChatServiceWithoutStore() {
        runner.withPropertyValues("app.production.session.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ProductionChatSessionStore.class);
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
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }

        @Bean
        LlmProviderRegistry llmProviderRegistry() {
            return mock(LlmProviderRegistry.class);
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
