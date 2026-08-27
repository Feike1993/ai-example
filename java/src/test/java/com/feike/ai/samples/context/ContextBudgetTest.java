package com.feike.ai.samples.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文预算纯逻辑，不调 LLM。
 */
@DisplayName("ContextBudget")
class ContextBudgetTest {

    @Test
    void trimShouldDropOldestTurnsUnderBudget() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("sys"));
        for (int i = 0; i < 10; i++) {
            history.add(new UserMessage("u" + i + " " + "x".repeat(40)));
            history.add(new AssistantMessage("a" + i + " " + "y".repeat(40)));
        }
        ContextBudget.TrimResult result = ContextBudget.trim(history, 6, 80);
        assertTrue(result.droppedCount() > 0);
        assertTrue(result.messages().size() <= 6);
        assertEquals("sys", result.messages().getFirst().getText());
        assertTrue(ContextBudget.approxTokens(result.messages()) <= 80
            || result.messages().size() <= 2);
    }

    @Test
    void summarizePlanShouldMarkNeedsSummaryWhenOverBudget() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("sys"));
        for (int i = 0; i < 8; i++) {
            history.add(new UserMessage("user-" + i + "-" + "z".repeat(50)));
            history.add(new AssistantMessage("asst-" + i + "-" + "z".repeat(50)));
        }
        ContextBudget.SummarizePlan plan = ContextBudget.planSummarize(history, 4, 8, 50);
        assertTrue(plan.needsSummary());
        assertFalse(plan.toSummarize().isEmpty());
        assertEquals(4, plan.recentTurns().size());
    }

    @Test
    void approxTokensIsCharLengthDiv4() {
        List<Message> messages = List.of(new UserMessage("abcd"));
        assertEquals(1, ContextBudget.approxTokens(messages));
    }
}
