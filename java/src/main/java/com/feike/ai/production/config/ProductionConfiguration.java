package com.feike.ai.production.config;

import com.feike.ai.production.agent.service.impl.ProductionAgentServiceImpl;
import com.feike.ai.production.chat.service.impl.ProductionChatServiceImpl;
import com.feike.ai.production.rag.ingest.service.impl.ProductionIngestServiceImpl;
import com.feike.ai.production.rag.retrieve.service.impl.ProductionRetrievalServiceImpl;

import com.feike.ai.core.config.AiProperties;
import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.production.agent.service.ProductionAgentService;
import com.feike.ai.production.audit.service.AuditService;
import com.feike.ai.production.auth.manager.DemoUserService;
import com.feike.ai.production.auth.manager.JwtService;
import com.feike.ai.production.chat.service.ProductionChatService;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.lock.manager.InMemorySessionLock;
import com.feike.ai.production.lock.manager.RedisSessionLock;
import com.feike.ai.production.lock.manager.SessionLock;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.rag.generate.service.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.ratelimit.manager.IdempotencyManager;
import com.feike.ai.production.ratelimit.manager.RedisTokenBucket;
import com.feike.ai.production.secret.manager.EnvSecretResolver;
import com.feike.ai.production.secret.dao.impl.JdbcSecretDAOImpl;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.secret.manager.SecretBootstrap;
import com.feike.ai.production.secret.dao.SecretResolver;
import com.feike.ai.production.session.dao.impl.JdbcProductionChatSessionDAOImpl;
import com.feike.ai.production.session.dao.ProductionChatSessionDAO;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import com.feike.ai.production.sse.dao.impl.RedisRunEventLogDAOImpl;
import com.feike.ai.production.sse.dao.RunEventLogDAO;
import com.feike.ai.production.sse.service.SseRunExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工业级链路的装配入口。
 * <p>
 * 整块按 {@code app.production.enabled} 开关，属性缺失时不装配：现有部署升级上来时
 * 不会凭空多出一组开放接口，要用必须显式打开。
 * <p>
 * 前置依赖是 {@link VectorStore}，也就是 {@code app.ai.rag.enabled=true}（默认开）。
 * 这里不再加 {@code @ConditionalOnBean(VectorStore.class)}：VectorStore 由 Spring AI 自动配置提供，
 * 而自动配置晚于用户配置类求值，条件会恒为假。宁可在两个开关矛盾时启动就报缺 Bean，
 * 也不要出现「开关明明打开了却没有接口」这种更难查的静默失效。
 * <p>
 * 服务层用 {@code @Bean} 显式声明而不是 {@code @Component} 扫描，是为了让「开关关掉就一个 Bean 都没有」
 * 这件事在一个文件里看得见，不必去翻十几个类上的条件注解。控制器是唯一例外——
 * Spring MVC 只对 {@code @Controller} 派生的 Bean 建路由，只能走扫描，因此它自带同一个开关条件。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.production", name = "enabled", havingValue = "true")
public class ProductionConfiguration {

    /**
     * Redis 事件日志：跨实例可续传，生产默认。
     *
     * @param redis      字符串模板
     * @param properties 保留窗口配置
     * @return 事件日志
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.stream", name = "event-log", havingValue = "redis", matchIfMissing = true)
    public RunEventLogDAO redisRunEventLog(StringRedisTemplate redis, ProductionProperties properties) {
        return new RedisRunEventLogDAOImpl(redis, properties);
    }

    /**
     * 内存事件日志：仅单进程演示与测试。
     *
     * @return 事件日志
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.stream", name = "event-log", havingValue = "memory")
    public RunEventLogDAO inMemoryRunEventLog() {
        return new InMemoryRunEventLogDAOImpl();
    }

    /**
     * @param eventLog   事件日志
     * @param jsonMapper 负载序列化
     * @param properties 心跳与超时
     * @return SSE 生命周期管家
     */
    @Bean
    public SseRunExecutor sseRunExecutor(
        RunEventLogDAO eventLog,
        JsonMapper jsonMapper,
        ProductionProperties properties
    ) {
        return new SseRunExecutor(eventLog, jsonMapper, properties);
    }

    /**
     * @param vectorStore      pgvector
     * @param keywordRetriever 全文路；RAG 关闭时不存在
     * @param properties       生产链路配置
     * @return 入库层
     */
    @Bean
    public ProductionIngestService productionIngestService(
        VectorStore vectorStore,
        ObjectProvider<RagKeywordRetriever> keywordRetriever,
        ProductionProperties properties
    ) {
        return new ProductionIngestServiceImpl(vectorStore, keywordRetriever.getIfAvailable(), properties);
    }

    /**
     * @param vectorStore      pgvector
     * @param keywordRetriever 全文路；缺失时退化为纯向量
     * @param properties       生产链路配置
     * @return 检索层
     */
    @Bean
    public ProductionRetrievalService productionRetrievalService(
        VectorStore vectorStore,
        ObjectProvider<RagKeywordRetriever> keywordRetriever,
        ProductionProperties properties
    ) {
        return new ProductionRetrievalServiceImpl(vectorStore, keywordRetriever.getIfAvailable(), properties);
    }

    /**
     * @param models 生产模型工厂
     * @return 生成层
     */
    @Bean
    public ProductionAnswerGenerator productionAnswerGenerator(ProductionModelFactory models) {
        return new ProductionAnswerGenerator(models);
    }

    /**
     * 会话存储。
     * <p>
     * 独立于 {@code app.production.enabled} 再加一层 {@code session.enabled}：
     * 它依赖 Flyway 迁移已经跑过（{@code prod_chat_session} / {@code prod_chat_message}），
     * 而链路的其余部分不依赖。schema 还没就位的环境可以只关这一项。
     *
     * @param jdbc               数据源模板
     * @param transactionManager JDBC starter 自动提供
     * @param properties         取重试次数
     * @return 会话存储
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.session", name = "enabled", havingValue = "true")
    public ProductionChatSessionDAO productionChatSessionStore(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager,
        ProductionProperties properties
    ) {
        return new JdbcProductionChatSessionDAOImpl(
            jdbc,
            new TransactionTemplate(transactionManager),
            properties.session().seqRetries()
        );
    }

    /**
     * Redis 会话锁：跨实例互斥，生产默认。
     *
     * @param redis      字符串模板
     * @param properties 取锁 TTL
     * @return 会话锁
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "app.production.session", name = "lock", havingValue = "redis", matchIfMissing = true)
    public SessionLock redisSessionLock(StringRedisTemplate redis, ProductionProperties properties) {
        return new RedisSessionLock(redis, properties.session().lockTtl());
    }

    /**
     * 进程内会话锁：仅单实例演示与测试。
     *
     * @return 会话锁
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.session", name = "lock", havingValue = "memory")
    public SessionLock inMemorySessionLock() {
        return new InMemorySessionLock();
    }

    /**
     * @param retrieval    检索层
     * @param generator    生成层
     * @param properties   生产链路配置
     * @param sessionStore 会话存储；{@code session.enabled=false} 时不存在
     * @param sessionLock  会话锁
     * @return 编排层
     */
    @Bean
    public ProductionChatService productionChatService(
        ProductionRetrievalService retrieval,
        ProductionAnswerGenerator generator,
        ProductionProperties properties,
        ObjectProvider<ProductionChatSessionDAO> sessionStore,
        ObjectProvider<SessionLock> sessionLock,
        ProductionGuardrail guardrail
    ) {
        return new ProductionChatServiceImpl(
            retrieval,
            generator,
            properties,
            sessionStore.getIfAvailable(),
            sessionLock.getIfAvailable(),
            guardrail
        );
    }

    /**
     * @param properties 词表
     * @param metrics    拦截计数
     * @return 护栏
     */
    @Bean
    public ProductionGuardrail productionGuardrail(ProductionProperties properties, ProductionMetrics metrics) {
        return new ProductionGuardrail(properties, metrics);
    }

    /**
     * @param jdbc JDBC
     * @param properties KEK
     * @return 信封存储
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "app.production.security", name = "secret-store", havingValue = "postgres", matchIfMissing = true)
    public SecretResolver jdbcSecretStore(JdbcTemplate jdbc, ProductionProperties properties) {
        return new JdbcSecretDAOImpl(jdbc, properties);
    }

    /**
     * @return 内存密钥
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.security", name = "secret-store", havingValue = "env")
    public SecretResolver envSecretStore() {
        return new EnvSecretResolver();
    }

    /**
     * @param secrets 存储
     * @param ai      首次灌入用
     * @return 启动灌入
     */
    @Bean
    public SecretBootstrap secretBootstrap(SecretResolver secrets, AiProperties ai) {
        return new SecretBootstrap(secrets, ai);
    }

    /**
     * @param ai      baseUrl/model
     * @param secrets 解密 key
     * @return 生产模型工厂
     */
    @Bean
    public ProductionModelFactory productionModelFactory(AiProperties ai, SecretResolver secrets) {
        return new ProductionModelFactory(ai, secrets);
    }

    /**
     * @param secrets 读取 jwt.hmac
     * @param properties TTL
     * @return JWT
     */
    @Bean
    public JwtService jwtService(SecretResolver secrets, ProductionProperties properties) {
        return new JwtService(secrets, properties.security().jwtTtl());
    }

    /**
     * @param properties 演示账号
     * @return 账号服务
     */
    @Bean
    public DemoUserService demoUserService(ProductionProperties properties) {
        return new DemoUserService(properties);
    }

    /**
     * @param redis      Redis
     * @param properties 桶参数
     * @return 令牌桶
     */
    @Bean
    public RedisTokenBucket redisTokenBucket(StringRedisTemplate redis, ProductionProperties properties) {
        return new RedisTokenBucket(redis, properties);
    }

    /**
     * @param redis      Redis
     * @param properties TTL
     * @param jsonMapper JSON
     * @return 幂等存储
     */
    @Bean
    public IdempotencyManager idempotencyStore(
        StringRedisTemplate redis,
        ProductionProperties properties,
        JsonMapper jsonMapper
    ) {
        return new IdempotencyManager(redis, properties, jsonMapper);
    }

    /**
     * @param jdbc JDBC
     * @return 审计
     */
    @Bean
    public AuditService auditService(JdbcTemplate jdbc) {
        return new AuditService(jdbc);
    }

    /**
     * @param registry Micrometer
     * @return 业务指标
     */
    @Bean
    public ProductionMetrics productionMetrics(MeterRegistry registry) {
        return new ProductionMetrics(registry);
    }

    /**
     * @param models       模型
     * @param retrieval    检索
     * @param ingest       入库
     * @param properties   配置
     * @param sessionStore 会话
     * @param sessionLock  锁
     * @param guardrail    护栏
     * @param metrics      指标
     * @return Agent 编排
     */
    @Bean
    public ProductionAgentService productionAgentService(
        ProductionModelFactory models,
        ProductionRetrievalService retrieval,
        ProductionIngestService ingest,
        ProductionProperties properties,
        ObjectProvider<ProductionChatSessionDAO> sessionStore,
        ObjectProvider<SessionLock> sessionLock,
        ProductionGuardrail guardrail,
        ProductionMetrics metrics
    ) {
        return new ProductionAgentServiceImpl(
            models,
            retrieval,
            ingest,
            properties,
            sessionStore.getIfAvailable(),
            sessionLock.getIfAvailable(),
            guardrail,
            metrics
        );
    }
}
