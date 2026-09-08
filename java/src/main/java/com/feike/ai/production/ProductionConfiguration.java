package com.feike.ai.production;

import com.feike.ai.core.LlmProviderRegistry;
import com.feike.ai.core.rag.RagKeywordRetriever;
import com.feike.ai.production.chat.ProductionChatService;
import com.feike.ai.production.rag.generate.ProductionAnswerGenerator;
import com.feike.ai.production.rag.ingest.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.ProductionRetrievalService;
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
     * @param retrieval  检索层
     * @param generator  生成层
     * @param properties 生产链路配置
     * @return 编排层
     */
    @Bean
    public ProductionChatService productionChatService(
        ProductionRetrievalService retrieval,
        ProductionAnswerGenerator generator,
        ProductionProperties properties
    ) {
        return new ProductionChatService(retrieval, generator, properties);
    }
}
