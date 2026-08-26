package com.feike.ai.samples.rag;

import com.feike.ai.core.AiProperties;
import com.feike.ai.core.LlmProviderRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 基础设施：向 Spring AI pgvector 自动配置提供 {@link EmbeddingModel}。
 * <p>
 * 关闭方式：{@code app.ai.rag.enabled=false}（同时请去掉或注释 datasource，避免空连库）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagInfrastructureConfiguration {

    /**
     * Embedding 固定走 {@code app.ai.embedding-provider}（默认 DashScope），与 Chat Provider 解耦。
     *
     * @param registry LLM 注册中心
     * @return EmbeddingModel Bean，供 PgVectorStore 注入
     */
    @Bean
    public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
        return registry.embeddingModel();
    }

    /**
     * 暴露分块 / topK 等，便于 Service 注入（避免到处读整个 AiProperties）。
     *
     * @param properties app.ai
     * @return RAG 子配置
     */
    @Bean
    public AiProperties.Rag ragSettings(AiProperties properties) {
        return properties.rag();
    }
}
