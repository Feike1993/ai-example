package com.feike.ai.core.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 查询扩展：把口语问题变成检索 query。
 * <p>
 * 改写句和 HyDE 假想文档<b>只用于检索</b>，不得写入 sources——来源必须仍是知识库原文。
 * 教学样例与生产链路共用同一套 prompt，避免两套话术漂移。
 * 调用失败时回退原问题，由调用方决定是否把策略降级为 {@code none}。
 */
public final class RagQueryExpander {

    private static final Logger log = LoggerFactory.getLogger(RagQueryExpander.class);

    /** 查询改写：只输出一行检索短句。 */
    public static final String REWRITE_SYSTEM =
        "把用户问题改写成适合检索的短句，保留关键实体与术语，只输出一行，不要解释。";

    /** HyDE：假想知识库段落，用作 Embedding 查询。 */
    public static final String HYDE_SYSTEM = """
        根据用户问题写一段可能出现在技术知识库中的假想回答段落。
        用陈述句、百科风格；不要编造库外专有产品细节；只输出段落正文，不要解释。
        """;

    private RagQueryExpander() {
    }

    /**
     * 一次 Chat 完成。生产侧走信封密钥的 ChatClient，单测可注入固定字符串。
     */
    @FunctionalInterface
    public interface Completion {

        /**
         * @param system 系统提示
         * @param user   用户消息
         * @return 模型正文；空或失败由实现抛异常或返回空
         */
        String complete(String system, String user);
    }

    /**
     * 把口语问题改写成检索短句。失败或空输出时返回原问题。
     *
     * @param question   用户问题
     * @param completion Chat 完成
     * @return 检索 query
     */
    public static String rewrite(String question, Completion completion) {
        if (question == null || question.isBlank() || completion == null) {
            return question;
        }
        try {
            String rewritten = completion.complete(REWRITE_SYSTEM, question);
            if (rewritten != null && !rewritten.isBlank()) {
                String trimmed = rewritten.trim();
                log.debug("RAG 查询改写: {} -> {}", question, trimmed);
                return trimmed;
            }
        } catch (RuntimeException ex) {
            log.warn("查询改写失败，使用原问题: {}", ex.toString());
        }
        return question;
    }

    /**
     * 生成假想文档作为向量检索 query。失败或空输出时返回原问题。
     *
     * @param question   用户问题
     * @param completion Chat 完成
     * @return 假想段落或原问题
     */
    public static String hypotheticalDocument(String question, Completion completion) {
        if (question == null || question.isBlank() || completion == null) {
            return question;
        }
        try {
            String hypo = completion.complete(HYDE_SYSTEM, question);
            if (hypo != null && !hypo.isBlank()) {
                return hypo.trim();
            }
        } catch (RuntimeException ex) {
            log.warn("HyDE 假想文档生成失败，回退原问题检索: {}", ex.toString());
        }
        return question;
    }
}
