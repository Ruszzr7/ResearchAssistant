package com.research.assistant.dto.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSelectedContentTest {

    @Test
    void appliesDifferentTextAndFormulaLimitsWithoutTruncation() {
        AgentSelectedContent text = selection("TEXT", "字".repeat(4_000));
        AgentSelectedContent formula = selection("FORMULA", "x".repeat(2_000));

        assertThat(text.exactText()).hasSize(4_000);
        assertThat(formula.exactText()).hasSize(2_000);
        assertThatThrownBy(() -> selection("TEXT", "字".repeat(4_001)))
                .hasMessage("选取内容过长");
        assertThatThrownBy(() -> selection("FORMULA", "x".repeat(2_001)))
                .hasMessage("选取内容过长");
    }

    private static AgentSelectedContent selection(String type, String text) {
        return new AgentSelectedContent("selection-1", 1L, "hash", 1, type, text, List.of());
    }
}
