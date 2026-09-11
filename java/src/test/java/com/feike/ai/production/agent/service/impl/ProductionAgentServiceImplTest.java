package com.feike.ai.production.agent.service.impl;

import com.feike.ai.production.agent.model.AgentTurnMedia;
import com.feike.ai.production.auth.model.ProductionPrincipal;
import com.feike.ai.production.chat.model.ChatDocument;
import com.feike.ai.production.config.ProductionProperties;
import com.feike.ai.production.guardrail.service.ProductionGuardrail;
import com.feike.ai.production.lock.manager.InMemorySessionLock;
import com.feike.ai.production.rag.ingest.service.ProductionIngestService;
import com.feike.ai.production.rag.retrieve.service.ProductionRetrievalService;
import com.feike.ai.production.secret.manager.ProductionModelFactory;
import com.feike.ai.production.session.dao.impl.FakeProductionChatSessionDAOImpl;
import com.feike.ai.production.sse.model.StreamEvent;
import com.feike.ai.production.sse.model.StreamEventTypeEnum;
import com.feike.ai.production.sse.service.EventSink;
import com.feike.ai.production.sse.service.SseStreamWriter;
import com.feike.ai.production.sse.dao.impl.InMemoryRunEventLogDAOImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProductionAgentServiceImpl attachments")
class ProductionAgentServiceImplTest {

    private static final ProductionPrincipal ALICE =
        new ProductionPrincipal("alice", "tenant-a", Set.of("USER"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void txtExtractShouldAppearInUserMessageWithoutVision() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("已根据附件作答"));
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        when(models.chatModel(any())).thenReturn(chatModel);

        ProductionAgentServiceImpl service = service(models, sessionStore());
        CollectingSink sink = new CollectingSink();
        AgentTurnMedia media = new AgentTurnMedia(
            List.of(new ChatDocument("note.txt", "text/plain", "hello doc", false, null)),
            "", 0, false, false);

        service.stream(writer(sink), ALICE, "s-1", "总结这份文件", "deepseek", media);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        verify(models, never()).visionClient(any());
        UserMessage user = lastUser(captor.getValue());
        assertTrue(user.getText().contains("hello doc"));
        assertTrue(user.getText().contains("note.txt"));
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        assertTrue(sink.dataOf(StreamEventTypeEnum.META).contains("\"hasDocument\":true"));
        assertTrue(sink.dataOf(StreamEventTypeEnum.META).contains("\"visionTranscribed\":false"));
    }

    @Test
    void imageTranscriptShouldGoToTextLoopWithoutMedia() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("图里是示意图"));
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        when(models.chatModel(any())).thenReturn(chatModel);

        ProductionAgentServiceImpl service = service(models, sessionStore());
        CollectingSink sink = new CollectingSink();
        AgentTurnMedia media = new AgentTurnMedia(
            List.of(), "图里写着 hello", 1, false, true);

        service.stream(writer(sink), ALICE, "s-1", "图里有什么", "deepseek", media);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        verify(models, never()).visionClient(any());
        verify(models, never()).visionChatModel(any());
        UserMessage user = lastUser(captor.getValue());
        assertTrue(user.getText().contains("图里写着 hello"));
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        assertTrue(sink.dataOf(StreamEventTypeEnum.META).contains("\"hasImage\":true"));
        assertTrue(sink.dataOf(StreamEventTypeEnum.META).contains("\"visionTranscribed\":true"));
    }

    @Test
    void persistShouldPrefixImageAndDocumentPlaceholders() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("好"));
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        when(models.chatModel(any())).thenReturn(chatModel);
        FakeProductionChatSessionDAOImpl store = sessionStore();
        ProductionAgentServiceImpl service = service(models, store);

        AgentTurnMedia media = new AgentTurnMedia(
            List.of(new ChatDocument("a.txt", "text/plain", "body", false, null)),
            "图转写", 1, false, true);
        service.stream(writer(new CollectingSink()), ALICE, "s-persist", "请处理", "deepseek", media);

        String user = store.history("tenant-a", "s-persist").getFirst().content();
        assertTrue(user.startsWith("[图片] [文档] "));
        assertFalse(user.contains("body"));
        assertFalse(user.contains("图转写"));
    }

    @Test
    void persistShouldNotDuplicateExistingPrefix() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(textResponse("好"));
        ProductionModelFactory models = mock(ProductionModelFactory.class);
        when(models.chatModel(any())).thenReturn(chatModel);
        FakeProductionChatSessionDAOImpl store = sessionStore();
        ProductionAgentServiceImpl service = service(models, store);

        AgentTurnMedia media = new AgentTurnMedia(
            List.of(new ChatDocument("a.txt", "text/plain", "body", false, null)),
            "", 0, false, false);
        service.stream(writer(new CollectingSink()), ALICE, "s-dup", "[文档] 已有", "deepseek", media);

        String user = store.history("tenant-a", "s-dup").getFirst().content();
        assertTrue(user.startsWith("[文档] 已有"));
        assertFalse(user.contains("[文档] [文档]"));
    }

    @Test
    void buildUserPromptPlainQuestionUnchanged() {
        assertTrue(ProductionAgentServiceImpl.buildUserPrompt("只问一句", AgentTurnMedia.none())
            .equals("只问一句"));
    }

    private static ProductionAgentServiceImpl service(
        ProductionModelFactory models,
        FakeProductionChatSessionDAOImpl store
    ) {
        ProductionProperties properties = new ProductionProperties(
            true, "prod-corpus", 4, 400, 1, true, 60, 4,
            null,
            new ProductionProperties.Session(true, "memory", 20, 2000, Duration.ofMinutes(1), 3),
            null, null, null, null, null, null
        );
        return new ProductionAgentServiceImpl(
            models,
            mock(ProductionRetrievalService.class),
            mock(ProductionIngestService.class),
            properties,
            store,
            new InMemorySessionLock(),
            new ProductionGuardrail(properties),
            null,
            null
        );
    }

    private static FakeProductionChatSessionDAOImpl sessionStore() {
        return new FakeProductionChatSessionDAOImpl();
    }

    private static SseStreamWriter writer(CollectingSink sink) {
        return new SseStreamWriter("run-agent", sink, new InMemoryRunEventLogDAOImpl(), JSON);
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    private static UserMessage lastUser(Prompt prompt) {
        UserMessage last = null;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof UserMessage user) {
                last = user;
            }
        }
        if (last == null) {
            throw new AssertionError("Prompt 里没有 UserMessage");
        }
        return last;
    }

    private static final class CollectingSink implements EventSink {

        private final List<StreamEvent> events = new ArrayList<>();

        @Override
        public void write(StreamEvent event) {
            events.add(event);
        }

        @Override
        public void heartbeat() {
        }

        @Override
        public void complete() {
        }

        String dataOf(StreamEventTypeEnum type) {
            return events.stream()
                .filter(event -> event.type() == type)
                .map(StreamEvent::data)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到事件: " + type));
        }
    }
}
