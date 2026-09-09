package com.feike.ai.production.guardrail.service;

import com.feike.ai.core.guardrail.DenyWordChecker;
import com.feike.ai.core.rag.CitationValidator;
import com.feike.ai.core.rag.CitedSource;
import com.feike.ai.production.observability.service.ProductionMetrics;
import com.feike.ai.production.config.ProductionProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产护栏：输入词表 → 引用必填 → 输出词表，全部 fail-closed。
 * <p>
 * 不把规则做成可绕过的「警告」：能被 prompt 说服关掉的护栏等于没有。
 */
public class ProductionGuardrail {

    private static final Pattern CITE = Pattern.compile("\\[C(\\d+)]", Pattern.CASE_INSENSITIVE);

    private final List<String> denyWords;
    private final ProductionMetrics metrics;

    /**
     * @param properties 词表
     */
    public ProductionGuardrail(ProductionProperties properties) {
        this(properties, null);
    }

    /**
     * @param properties 词表
     * @param metrics    拦截计数；可空
     */
    public ProductionGuardrail(ProductionProperties properties, ProductionMetrics metrics) {
        this.denyWords = DenyWordChecker.normalize(properties.security().denyWords());
        this.metrics = metrics;
    }

    /**
     * 检查用户输入。
     *
     * @param text 问题
     */
    public void checkInput(String text) {
        String hit = DenyWordChecker.firstHit(text, denyWords);
        if (hit != null) {
            blocked();
            throw new GuardrailBlockedException("input_deny", "输入命中敏感词，已拒绝处理");
        }
    }

    /**
     * 检查模型输出。
     *
     * @param text 答案
     */
    public void checkOutput(String text) {
        String hit = DenyWordChecker.firstHit(text, denyWords);
        if (hit != null) {
            blocked();
            throw new GuardrailBlockedException("output_deny", "输出命中敏感词，已拦截");
        }
    }

    /**
     * 有检索命中时，答案必须带至少一个落在本次 hits 的 {@code [C#]} 引用。
     *
     * @param answer  模型答案
     * @param sources 本次检索
     */
    public void checkCitations(String answer, List<? extends CitedSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        var aliases = CitationValidator.aliasMap(sources);
        List<CitationValidator.Citation> citations = new ArrayList<>();
        Matcher matcher = CITE.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            String alias = "C" + matcher.group(1);
            citations.add(new CitationValidator.Citation(alias, ""));
        }
        List<CitationValidator.Citation> resolved = CitationValidator.resolveCitations(citations, aliases, sources);
        CitationValidator.Result result = CitationValidator.validate(resolved, CitationValidator.idsOf(sources));
        if (!result.valid()) {
            blocked();
            throw new GuardrailBlockedException("citation_required", "回答缺少有效引用，已拒绝展示");
        }
    }

    private void blocked() {
        if (metrics != null) {
            metrics.guardrail();
        }
    }
}
