package com.research.assistant.service.ai;

import com.research.assistant.dto.LlmResponse;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void shouldIncludeMultimodalPartsInInputBudgetEstimate() {
        LlmCallPolicy policy = new LlmCallPolicy("test", 100_000, 1_027, 20, 1);
        List<Content> contents = List.of(
                TextContent.from("123456789"),
                ImageContent.from("aGVsbG8=", "image/jpeg"));

        assertThat(policy.estimateInputTokens("1234", contents)).isEqualTo(1_028);
        assertThat(policy.exceedsInputBudget("1234", contents)).isTrue();
    }
}
