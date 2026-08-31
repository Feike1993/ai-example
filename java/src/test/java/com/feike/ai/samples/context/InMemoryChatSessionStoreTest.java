package com.feike.ai.samples.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存会话存储单测（不依赖数据库）。
 */
@DisplayName("InMemoryChatSessionStore")
class InMemoryChatSessionStoreTest {

    @Test
    void shouldPersistTurnsAndClear() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore();
        String id = store.resolveSessionId("demo-1");
        store.replace(id, List.of(new SystemMessage("sys")));
        store.appendTurn(id, "你好", "你好呀");

        List<Message> snapshot = store.snapshot(id);
        assertEquals(3, snapshot.size());
        assertEquals("memory", store.storeKind());
        assertEquals(3, store.views(id).size());

        assertTrue(store.clear(id));
        assertFalse(store.clear(id));
        assertTrue(store.snapshot(id).isEmpty());
    }

    @Test
    void fromRoleShouldMapRoles() {
        assertTrue(ChatSessionStore.fromRole("user", "u") instanceof UserMessage);
        assertTrue(ChatSessionStore.fromRole("assistant", "a") instanceof AssistantMessage);
        assertTrue(ChatSessionStore.fromRole("system", "s") instanceof SystemMessage);
    }
}
