package com.feike.ai.production.rag.retrieve.model;

/**
 * 生产检索条件。
 *
 * @param question        用户问题
 * @param topK            覆盖默认 topK；{@code null} 用配置
 * @param tenantId        JWT 租户
 * @param queryExpansion  none / rewrite / hyde；空则用配置默认（通常 none）
 * @param provider        Chat Provider，仅 rewrite / HyDE 需要
 */
public record ProductionRetrieveQuery(
    String question,
    Integer topK,
    String tenantId,
    String queryExpansion,
    String provider
) {

    /**
     * 无扩展的检索（Agent {@code search_kb}、默认问答）。
     *
     * @param question 用户问题
     * @param topK     topK
     * @param tenantId 租户
     * @return 查询
     */
    public static ProductionRetrieveQuery of(String question, Integer topK, String tenantId) {
        return new ProductionRetrieveQuery(question, topK, tenantId, null, null);
    }
}
