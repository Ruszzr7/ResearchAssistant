package com.research.assistant.service.ai;

import com.research.assistant.dto.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCallPolicyTest {

    @Test
    void shouldBoundInputAndEstimatePromptTokens() {
        LlmCallPolicy policy = new LlmCallPolicy("test", 4, 10, 20, 1);

        assertThat(policy.limitInput("123456")).isEqualTo("1234");
        assertThat(policy.estimatePromptTokens("1234", "5678")).isEqualTo(2);
    }

    @Test
    void shouldDetectOutputBudgetOverflow() {
        LlmCallPolicy policy = new LlmCallPolicy("test", 100, 20, 2, 1);

        assertThat(policy.exceedsOutputBudget(new LlmResponse("ok", 1, 3, 4))).isTrue();
        assertThat(policy.exceedsOutputBudget(new LlmResponse("ok", 1, 2, 3))).isFalse();
        assertThat(policy.exceedsOutputBudget(null)).isTrue();
        assertThat(policy.exceedsInputBudget("1234", "5678")).isFalse();
    }
}
