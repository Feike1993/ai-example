package com.feike.ai.production.rag.generate.service;

import com.feike.ai.production.chat.model.ChatDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProductionAnswerGenerator 文档摘录")
class ProductionAnswerGeneratorTest {

    @Test
    void userMessageShouldIncludeExtractedTextNotAsMedia() {
        ChatDocument doc = new ChatDocument("note.txt", "text/plain", "hello extract", false, null);
        List<Message> messages = ProductionAnswerGenerator.buildMessages(
            "总结",
            List.of(),
            List.of(),
            List.of(),
            List.of(doc)
        );
        assertTrue(messages.get(0) instanceof SystemMessage);
        UserMessage user = (UserMessage) messages.get(1);
        assertTrue(user.getText().contains("本轮附件文本"));
        assertTrue(user.getText().contains("note.txt"));
        assertTrue(user.getText().contains("hello extract"));
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
    }

    @Test
    void retrievalHitsStayAheadOfDocumentExtract() {
        Document hit = Document.builder().id("c1").text("知识库句子").metadata(java.util.Map.of("source", "a.md")).build();
        String text = ProductionAnswerGenerator.buildUserMessage(
            "问",
            List.of(hit),
            List.of(new ChatDocument("a.txt", "text/plain", "附件句子", false, null))
        );
        int ctx = text.indexOf("知识库句子");
        int att = text.indexOf("附件句子");
        int q = text.indexOf("用户问题");
        assertTrue(ctx >= 0 && att > ctx && q > att);
    }
}
