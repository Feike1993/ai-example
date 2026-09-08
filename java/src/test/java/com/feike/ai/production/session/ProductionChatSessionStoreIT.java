package com.feike.ai.production.session;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话存储的事务与并发，跑在真实 PostgreSQL 上。
 * <p>
 * 这几条性质<b>只能</b>在真实数据库上验证：事务回滚、复合主键在并发下的仲裁、
 * 唯一索引的幂等兜底，全都是数据库行为。在内存替身里模拟出来的「并发安全」毫无证明力。
 * <p>
 * 跑法：{@code RUN_SESSION_IT=true ./gradlew test --tests ProductionChatSessionStoreIT}
 * <p>
 * 用普通 postgres 镜像而不是 pgvector：这两张表跟向量检索无关，省掉一个大得多的镜像。
 * <p>
 * 设了 {@code SESSION_IT_JDBC_URL} 就直接连那个库，不再起容器。CI 上常见的是
 * 由流水线以 service 形式提供数据库，容器套容器反而跑不起来；本机 Testcontainers
 * 与某些 Docker 版本不兼容时，这也是唯一能把测试跑起来的路子。
 */
@DisplayName("ProductionChatSessionStoreIT")
@EnabledIfEnvironmentVariable(named = "RUN_SESSION_IT", matches = "true")
class ProductionChatSessionStoreIT {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private JdbcProductionChatSessionStore store;

    @BeforeAll
    static void startDatabase() {
        String url = System.getenv("SESSION_IT_JDBC_URL");
        String user = envOrDefault("SESSION_IT_JDBC_USER", "ai");
        String password = envOrDefault("SESSION_IT_JDBC_PASSWORD", "ai");
        if (url == null || url.isBlank()) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("ai_example")
                .withUsername(user)
                .withPassword(password);
            postgres.start();
            url = postgres.getJdbcUrl();
        }

        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
        // 顺带验证迁移脚本本身能跑通；复用外部库时 baseline 让已有表也能接管
        Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE prod_chat_session CASCADE");
        store = newStore(3);
    }

    @Test
    void appendTurnShouldWriteBothMessagesWithSharedTurnIdAndSeq() {
        UUID turnId = UUID.randomUUID();
        store.appendTurn("s-1", turnId, "run-1", "问题", "回答");

        List<SessionMessage> history = store.history("s-1");
        assertEquals(2, history.size());
        assertEquals(0, history.get(0).seq());
        assertEquals(1, history.get(1).seq());
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
        assertEquals(turnId.toString(), history.get(0).turnId());
        assertEquals(turnId.toString(), history.get(1).turnId());
        assertEquals("run-1", history.get(0).runId());
    }

    @Test
    void sameTurnIdShouldBeIdempotent() {
        UUID turnId = UUID.randomUUID();
        store.appendTurn("s-2", turnId, "run-1", "问题", "回答");
        store.appendTurn("s-2", turnId, "run-1", "问题", "回答");
        store.appendTurn("s-2", turnId, "run-2", "问题（重复提交）", "回答");

        assertEquals(2, store.history("s-2").size());
    }

    @Test
    void failureMidTurnShouldRollBackTheUserMessage() {
        // PostgreSQL 的 text 不接受 NUL 字节，用它在 assistant 那条 INSERT 上制造一次真实失败。
        // 关键在于此刻 user 那条已经执行过了——没有事务的话它会留下来变成孤儿。
        assertThrows(
            DataAccessException.class,
            () -> store.appendTurn("s-3", UUID.randomUUID(), "run-1", "问题", "回答\u0000")
        );

        assertTrue(store.history("s-3").isEmpty(), "整轮应一起回滚，不留孤儿 user 消息");
    }

    @Test
    void concurrentAppendsShouldProduceContiguousSeqWithoutLoss() throws Exception {
        int threads = 8;
        // 重试预算要跟并发度匹配：默认的 3 次是给正常流量准备的，
        // 这里刻意让 8 个线程同时抢同一个 session 的号，冲突率远高于真实场景
        JdbcProductionChatSessionStore contended = newStore(50);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        contended.appendTurn("s-hot", UUID.randomUUID(), "run-" + index,
                            "问题" + index, "回答" + index);
                    } catch (Throwable ex) {
                        synchronized (failures) {
                            failures.add(ex);
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发写入应在超时前完成");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "并发写入不应有失败: " + failures);
        List<SessionMessage> history = contended.history("s-hot");
        assertEquals(threads * 2, history.size(), "每轮两条，一条都不能丢");
        for (int i = 0; i < history.size(); i++) {
            assertEquals(i, history.get(i).seq(), "seq 必须连续且不重复");
        }
        // 每轮的 user 与 assistant 必须挨着：说明并发的两轮没有被交叉插入
        for (int i = 0; i < history.size(); i += 2) {
            assertEquals(history.get(i).turnId(), history.get(i + 1).turnId());
            assertEquals("user", history.get(i).role());
            assertEquals("assistant", history.get(i + 1).role());
        }
    }

    @Test
    void clearShouldCascadeToMessages() {
        store.appendTurn("s-4", UUID.randomUUID(), "run-1", "问题", "回答");

        assertTrue(store.clear("s-4"));
        assertTrue(store.history("s-4").isEmpty());
        assertEquals(
            0,
            (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM prod_chat_message WHERE session_id = ?", Integer.class, "s-4")
        );
        assertFalse(store.clear("s-4"), "重复清空应返回 false");
    }

    private static JdbcProductionChatSessionStore newStore(int maxAttempts) {
        return new JdbcProductionChatSessionStore(
            jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            maxAttempts
        );
    }
}
