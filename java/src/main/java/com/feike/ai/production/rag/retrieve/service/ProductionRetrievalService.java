package com.feike.ai.production.rag.retrieve.service;

import com.feike.ai.production.rag.model.ProductionSource;
import org.springframework.ai.document.Document;
import java.util.List;

/** ProductionRetrievalService 业务门面。 */
public interface ProductionRetrievalService {

    String META_TENANT_ID = "tenant_id";

    /**
     * 检索结果。
     *
     * @param hits          原始命中，供生成层拼上下文
     * @param sources       对外视图
     * @param empty         是否空检索（命中数低于 minSources）
     * @param retrievalMode 实际走的检索模式，便于排查降级
     */
    record RetrievalResult(
        List<Document> hits,
        List<ProductionSource> sources,
        boolean empty,
        String retrievalMode
    ) {}

    RetrievalResult retrieve(String question, Integer topK);

    RetrievalResult retrieve(String question, Integer topK, String tenantId);

    /**
     * 租户 id 只允许字母数字与 {@code _-}，避免拼进 filter 表达式时被注入。
     *
     * @param tenantId 原始租户
     * @return 安全值
     */
    static String sanitizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "default";
        }
        String trimmed = tenantId.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        return out.isEmpty() ? "default" : out.toString();
    }
}
