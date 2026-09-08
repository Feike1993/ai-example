package com.feike.ai.production;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.production.chat.ProductionChatService;
import com.feike.ai.production.lock.InMemorySessionLock;
import com.feike.ai.production.lock.RedisSessionLock;
import com.feike.ai.production.lock.SessionLock;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
import com.feike.ai.production.session.JdbcProductionChatSessionStore;
import com.feike.ai.production.session.ProductionChatSessionStore;
import com.feike.ai.production.sse.InMemoryRunEventLog;
import com.feike.ai.production.sse.RedisRunEventLog;
import com.feike.ai.production.sse.RunEventLog;
import com.feike.ai.production.sse.SseRunExecutor;
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
    public RunEventLog redisRunEventLog(StringRedisTemplate redis, ProductionProperties properties) {
        return new RedisRunEventLog(redis, properties);
    }

    /**
     * 内存事件日志：仅单进程演示与测试。
     *
     * @return 事件日志
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.production.stream", name = "event-log", havingValue = "memory")
    public RunEventLog inMemoryRunEventLog() {
        return new InMemoryRunEventLog();
    }

    /**
     * @param eventLog   事件日志
     * @param jsonMapper 负载序列化
     * @param properties 心跳与超时
     * @return SSE 生命周期管家
     */
    @Bean
    public SseRunExecutor sseRunExecutor(
        RunEventLog eventLog,
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
        return new ProductionIngestService(vectorStore, keywordRetriever.getIfAvailable(), properties);
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
        return new ProductionRetrievalService(vectorStore, keywordRetriever.getIfAvailable(), properties);
    }

    /**
     * @param registry 多 Provider 注册表
     * @return 生成层
     */
    @Bean
    public ProductionAnswerGenerator productionAnswerGenerator(LlmProviderRegistry registry) {
        return new ProductionAnswerGenerator(registry);
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
    public ProductionChatSessionStore productionChatSessionStore(
        JdbcTemplate jdbc,
        PlatformTransactionManager transactionManager,
        ProductionProperties properties
    ) {
        return new JdbcProductionChatSessionStore(
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
        ObjectProvider<ProductionChatSessionStore> sessionStore,
        ObjectProvider<SessionLock> sessionLock
    ) {
        return new ProductionChatService(
            retrieval,
            generator,
            properties,
            sessionStore.getIfAvailable(),
            sessionLock.getIfAvailable()
        );
    }
}
