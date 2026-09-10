package com.feike.ai.core.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("RagQueryExpander")
class RagQueryExpanderTest {

    @Test
    void rewriteShouldReturnModelOutput() {
        String rewritten = RagQueryExpander.rewrite("这个项目的 RAG 是怎么做的？", (system, user) -> "RAG 实现");
        assertEquals("RAG 实现", rewritten);
    }

    @Test
    void rewriteShouldFallBackToOriginalWhenEmptyOrFailed() {
        String question = "原问题";
        assertEquals(question, RagQueryExpander.rewrite(question, (system, user) -> "  "));
        assertEquals(question, RagQueryExpander.rewrite(question, (system, user) -> {
            throw new IllegalStateException("模型网关 502");
        }));
    }

    @Test
    void hydeShouldReturnHypotheticalParagraphNotTheQuestion() {
        String hypo = RagQueryExpander.hypotheticalDocument(
            "什么是 RAG",
            (system, user) -> "检索增强生成把检索段落拼进提示词。"
        );
        assertEquals("检索增强生成把检索段落拼进提示词。", hypo);
    }

    @Test
    void blankQuestionShouldSkipCompletion() {
        String blank = "  ";
        assertSame(blank, RagQueryExpander.rewrite(blank, (system, user) -> "不该调用"));
    }
}
