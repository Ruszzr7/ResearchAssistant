package com.research.assistant.service.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunFailureClassifierTest {

    @Test
    void distinguishesTimeoutPayloadEvidenceAndConnectionFailures() {
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("request timeout")).code())
                .isEqualTo("MODEL_TIMEOUT");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("tool result exceeded the model payload limit")).code())
                .isEqualTo("TOOL_OUTPUT_OVERSIZE");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("citation source was not read")).code())
                .isEqualTo("EVIDENCE_VALIDATION_FAILED");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("socket connection reset")).code())
                .isEqualTo("MODEL_CONNECTION_FAILED");
    }

    @Test
    void classifiesEmptyModelOutputAndKeepsLegacyProtocolMessageReadable() {
        assertThat(AgentRunFailureClassifier.classify(
                new RuntimeException("model returned an empty response")).code())
                .isEqualTo("MODEL_EMPTY_RESPONSE");
        assertThat(AgentRunFailureClassifier.userMessage("MODEL_EMPTY_RESPONSE"))
                .isEqualTo("模型未返回有效内容，请重试");
        assertThat(AgentRunFailureClassifier.userMessage("AGENT_PROTOCOL_ERROR"))
                .isEqualTo("模型返回格式不符合要求，请重试");
    }
}
