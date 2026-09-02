package com.feike.ai.samples.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG citation 后校验：每条 sourceId 必须落在本次检索 hits。
 */
public final class CitationValidator {

    private CitationValidator() {}

    /**
     * 单条引用。
     *
     * @param sourceId 对应 {@link RagSampleService.SourceView#id()}
     * @param quote    摘录或依据说明
     */
    public record Citation(String sourceId, String quote) {}

    /**
     * 结构化 grounded 答（模型输出）。
     *
     * @param answer    面向用户的答案
     * @param citations 引用列表
     */
    public record GroundedAnswer(String answer, List<Citation> citations) {}

    /**
     * 校验结果。
     *
     * @param valid   是否通过
     * @param detail  失败原因；通过时为简短说明
     */
    public record Result(boolean valid, String detail) {}

    /**
     * 校验 citations 非空且 sourceId 均在 allowedIds 内。
     *
     * @param citations  模型给出的引用
     * @param allowedIds 本次检索文档 id
     * @return 校验结果
     */
    public static Result validate(List<Citation> citations, Set<String> allowedIds) {
        if (citations == null || citations.isEmpty()) {
            return new Result(false, "citations 为空");
        }
        Set<String> allowed = allowedIds == null ? Set.of() : allowedIds;
        List<String> missing = new ArrayList<>();
        for (Citation citation : citations) {
            if (citation == null || citation.sourceId() == null || citation.sourceId().isBlank()) {
                return new Result(false, "存在空 sourceId");
            }
            if (!allowed.contains(citation.sourceId())) {
                missing.add(citation.sourceId());
            }
        }
        if (!missing.isEmpty()) {
            return new Result(false, "sourceId 不在检索结果: " + String.join(", ", missing));
        }
        return new Result(true, "citations 均落在检索 hits（" + citations.size() + " 条）");
    }

    /**
     * 从 SourceView 列表收集 id。
     *
     * @param sources 检索 sources
     * @return 有序去重 id 集
     */
    public static Set<String> idsOf(List<RagSampleService.SourceView> sources) {
        Set<String> ids = new LinkedHashSet<>();
        if (sources == null) {
            return ids;
        }
        for (RagSampleService.SourceView source : sources) {
            if (source != null && source.id() != null && !source.id().isBlank()) {
                ids.add(source.id());
            }
        }
        return ids;
    }
}
