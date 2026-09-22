package com.feike.ai.production.rag.config;

import com.feike.ai.production.secret.manager.ProductionModelFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工业级链路的独立 pgvector 门面。它与教学链路共用表结构，但查询和写入时使用
 * 模型设置中的 embedding 路由，不复用教学侧静态 EmbeddingModel。
 */
public final class ProductionVectorStoreManager {

    private final VectorStore vectorStore;

    public ProductionVectorStoreManager(
        JdbcTemplate jdbc,
        ProductionModelFactory models,
        PgVectorStoreProperties properties
    ) {
        int dimensions = properties.getDimensions();
        if (dimensions < 1) {
            throw new IllegalStateException("spring.ai.vectorstore.pgvector.dimensions 必须显式配置");
        }
        EmbeddingModel embedding = new SettingsEmbeddingModel(models, dimensions);
        this.vectorStore = PgVectorStore.builder(jdbc, embedding)
            .schemaName(properties.getSchemaName())
            .idType(properties.getIdType())
            .vectorTableName(properties.getTableName())
            .vectorTableValidationsEnabled(properties.isSchemaValidation())
            .dimensions(dimensions)
            .distanceType(properties.getDistanceType())
            .removeExistingVectorStoreTable(false)
            .indexType(properties.getIndexType())
            .initializeSchema(false)
            .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
            .build();
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    private record SettingsEmbeddingModel(ProductionModelFactory models, int dimensions)
        implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return models.embeddingModel(dimensions).call(request);
        }

        @Override
        public float[] embed(Document document) {
            return models.embeddingModel(dimensions).embed(document);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }
}
