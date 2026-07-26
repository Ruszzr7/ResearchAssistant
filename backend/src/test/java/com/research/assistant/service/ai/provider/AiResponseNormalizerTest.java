package com.research.assistant.service.ai.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseNormalizerTest {

    @Test
    void removesMiniMaxLeadingThinkingBlockOnly() {
        String content = "<think>internal reasoning</think>\n最终答案";

        assertThat(AiResponseNormalizer.finalContent(AiProvider.MINIMAX, content))
                .isEqualTo("最终答案");
        assertThat(AiResponseNormalizer.finalContent(AiProvider.OPENAI, content))
                .isEqualTo(content);
    }

    @Test
    void handlesNullContent() {
        assertThat(AiResponseNormalizer.finalContent(AiProvider.KIMI, null)).isEmpty();
    }
}
