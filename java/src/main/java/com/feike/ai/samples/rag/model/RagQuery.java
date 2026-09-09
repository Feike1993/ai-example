package com.feike.ai.samples.rag.model;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 查询请求（条件多于 2 个，用 Query 而非裸参数）。
 *
 * @param question         用户问题
 * @param provider         可选 Chat Provider
 * @param topK             可选覆盖默认 topK
 * @param retrievalMode    {@code vector}（默认）或 {@code hybrid}
 * @param rewriteQuery     是否改写问题后再检索（兼容；等价 queryExpansion=rewrite）
 * @param queryExpansion   {@code none} / {@code rewrite} / {@code hyde} / {@code memory_rewrite}
 * @param chunkingStrategy {@code token}（默认）或 {@code semantic} / {@code parent_child}
 * @param citationMode     {@code none}（默认）或 {@code required}
 * @param userId           memory_rewrite / compare-memory 用
 * @param memoryTopK       记忆召回条数
 * @param generateAnswers  compare-memory 是否生成答案
 */
public record RagQuery(
    @NotBlank String question,
    String provider,
    Integer topK,
    String retrievalMode,
    Boolean rewriteQuery,
    String queryExpansion,
    String chunkingStrategy,
    String citationMode,
    String userId,
    Integer memoryTopK,
    Boolean generateAnswers
) {}
