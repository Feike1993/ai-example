package com.feike.ai.production.rag;

import com.feike.ai.core.rag.CitedSource;

import java.util.Map;

/**
 * 生产链路对外暴露的检索命中视图。
 * <p>
 * 刻意比样例的 SourceView 窄：不带 vectorRank / keywordRank / rrfScore 这些「用于教学对比」的字段。
 * 生产前端要展示的是「答案依据哪几段文字」，检索内部的排名细节属于可观测性数据，
 * 应该走日志和指标，而不是塞进每一次用户响应里。
 *
 * @param id      文档 id，citation 校验的锚点
 * @param source  来源文件名
 * @param excerpt 摘录正文
 * @param heading 所属章节标题；无标题时为 {@code null}
 */
public record ProductionSource(
    String id,
    String source,
    String excerpt,
    String heading
) implements CitedSource {

    /**
     * 从向量库文档构造视图。
     *
     * @param id         文档 id
     * @param text       chunk 正文
     * @param metadata   chunk 元数据
     * @param maxExcerpt 摘录上限；{@code <= 0} 表示保留全文
     * @return 视图
     */
    public static ProductionSource of(String id, String text, Map<String, Object> metadata, int maxExcerpt) {
        String body = text == null ? "" : text;
        String excerpt = (maxExcerpt > 0 && body.length() > maxExcerpt)
            ? body.substring(0, maxExcerpt) + "…"
            : body;
        Map<String, Object> meta = metadata == null ? Map.of() : metadata;
        Object heading = meta.get("heading");
        return new ProductionSource(
            id,
            String.valueOf(meta.getOrDefault("source", "")),
            excerpt,
            heading == null ? null : String.valueOf(heading)
        );
    }
}
