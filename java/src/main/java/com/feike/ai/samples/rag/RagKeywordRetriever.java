package com.feike.ai.samples.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 全文检索路：在 {@code vector_store.content} 上做 {@code plainto_tsquery}。
 * <p>
 * ingest 后调用 {@link #ensureFullTextIndex()} 创建 GIN 索引（幂等）。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagKeywordRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagKeywordRetriever.class);

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final String tableName;
    private volatile boolean indexEnsured;

    /**
     * @param jdbcTemplate 查询 vector_store
     * @param jsonMapper   解析 metadata JSON
     * @param tableName    与 Spring AI pgvector 表名一致
     */
    public RagKeywordRetriever(
        JdbcTemplate jdbcTemplate,
        JsonMapper jsonMapper,
        @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.tableName = sanitizeTableName(tableName);
    }

    /**
     * 幂等创建 content 全文 GIN 索引。
     */
    public void ensureFullTextIndex() {
        if (indexEnsured) {
            return;
        }
        synchronized (this) {
            if (indexEnsured) {
                return;
            }
            String indexName = "idx_" + tableName + "_content_fts";
            String sql = """
                CREATE INDEX IF NOT EXISTS %s ON %s USING gin (to_tsvector('simple', coalesce(content, '')))
                """.formatted(indexName, tableName);
            try {
                jdbcTemplate.execute(sql);
                indexEnsured = true;
                log.info("RAG 全文索引已就绪: {}", indexName);
            } catch (Exception ex) {
                log.warn("创建全文索引失败（关键词检索可能降级）: {}", ex.toString());
            }
        }
    }

    /**
     * 关键词全文检索，按 ts_rank 降序。
     *
     * @param query  用户问题
     * @param topK   返回条数
     * @param corpus 语料 metadata 过滤值
     * @return Document 列表（id / content / metadata）
     */
    public List<Document> search(String query, int topK, String corpus) {
        ensureFullTextIndex();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int k = topK < 1 ? 4 : topK;
        String sql = """
            SELECT id::text AS id, content, metadata::text AS metadata_json,
                   ts_rank(to_tsvector('simple', coalesce(content, '')),
                           plainto_tsquery('simple', ?)) AS rank
            FROM %s
            WHERE metadata->>'corpus' = ?
              AND to_tsvector('simple', coalesce(content, '')) @@ plainto_tsquery('simple', ?)
            ORDER BY rank DESC
            LIMIT ?
            """.formatted(tableName);
        try {
            return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    Map<String, Object> metadata = parseMetadata(rs.getString("metadata_json"));
                    return Document.builder()
                        .id(id)
                        .text(content)
                        .metadata(metadata)
                        .build();
                },
                query,
                corpus,
                query,
                k
            );
        } catch (Exception ex) {
            log.warn("关键词检索失败: {}", ex.toString());
            return fallbackIlike(query, k, corpus);
        }
    }

    private List<Document> fallbackIlike(String query, int topK, String corpus) {
        String pattern = "%" + query.trim().replace("%", "") + "%";
        String sql = """
            SELECT id::text AS id, content, metadata::text AS metadata_json
            FROM %s
            WHERE metadata->>'corpus' = ?
              AND content ILIKE ?
            LIMIT ?
            """.formatted(tableName);
        try {
            return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> Document.builder()
                    .id(rs.getString("id"))
                    .text(rs.getString("content"))
                    .metadata(parseMetadata(rs.getString("metadata_json")))
                    .build(),
                corpus,
                pattern,
                topK
            );
        } catch (Exception ex) {
            log.warn("ILIKE 降级检索失败: {}", ex.toString());
            return List.of();
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String sanitizeTableName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_]+")) {
            return "vector_store";
        }
        return name;
    }
}
