package com.research.assistant.dto.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTurnInputTest {

    @Test
    void acceptsMessageOrExplicitActionAndNormalizesCollections() {
        AgentTurnInput message = new AgentTurnInput(1, 2L, " 解释它 ", null,
                null, null, null, null, "request-1", null);
        assertThat(message.userMessage()).isEqualTo("解释它");
        assertThat(message.attachmentIds()).isEmpty();

        AgentTurnInput action = new AgentTurnInput(1, 2L, null,
                new AgentExplicitAction("navigate", Map.of("page", 3)), null,
                List.of(), List.of(), null, "request-2", null);
        assertThat(action.explicitAction().type()).isEqualTo("NAVIGATE");
    }

    @Test
    void rejectsEmptyTurnAndUnboundedAttachments() {
        assertThatThrownBy(() -> new AgentTurnInput(1, 2L, null, null,
                null, null, null, null, "request", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentTurnInput(1, 2L, "question", null,
                null, List.of("1", "2", "3", "4"), null, null, "request", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesMessageAndCombinedAttachmentLimits() {
        assertThatThrownBy(() -> new AgentTurnInput(1, 2L, "问".repeat(2_001), null,
                null, null, null, null, "request", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("输入内容过长");
        assertThatThrownBy(() -> new AgentTurnInput(1, 2L, "question", null,
                null, List.of("file-1"), List.of("formula-1", "formula-2"), null, "request", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("每条消息最多添加 2 个附件");

        AgentTurnInput accepted = new AgentTurnInput(1, 2L, "问".repeat(2_000), null,
                null, List.of("file-1"), List.of("formula-1"), null, "request", null);
        assertThat(accepted.userMessage()).hasSize(2_000);
    }
}
