package com.feike.ai.samples.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pgvector 集成测：需要本机 Docker，且设置 {@code RUN_PGVECTOR_IT=true}。
 * <p>
 * 跑法：{@code RUN_PGVECTOR_IT=true ./gradlew test --tests RagPgvectorIT}
 * （仍需要可连的 DashScope Key 才会真正 ingest；本测试只验证容器与 Schema 可起。）
 */
@DisplayName("RagPgvectorIT")
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_PGVECTOR_IT", matches = "true")
class RagPgvectorIT {

    private static final DockerImageName IMAGE =
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(IMAGE)
        .withDatabaseName("ai_example")
        .withUsername("ai")
        .withPassword("ai");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.ai.rag.enabled", () -> "true");
        registry.add("spring.ai.vectorstore.type", () -> "pgvector");
    }

    @Autowired(required = false)
    private RagSampleService ragSampleService;

    @Test
    void contextLoadsWithPgvector() {
        assertTrue(postgres.isRunning());
        assertFalse(ragSampleService == null, "RagSampleService 应被注入");
    }
}
