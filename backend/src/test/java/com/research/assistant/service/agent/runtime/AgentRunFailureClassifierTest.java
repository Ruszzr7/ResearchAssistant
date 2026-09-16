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
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("ANSWER_SUBMISSION_REQUIRED")).code())
                .isEqualTo("ANSWER_SUBMISSION_REQUIRED");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("socket connection reset")).code())
                .isEqualTo("MODEL_CONNECTION_FAILED");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("CONTEXT_BUDGET_EXCEEDED")).code())
                .isEqualTo("CONTEXT_BUDGET_EXCEEDED");
        assertThat(AgentRunFailureClassifier.classify(new RuntimeException("TOOL_CALL_LIMIT_EXCEEDED")).code())
                .isEqualTo("AGENT_CALL_LIMIT");
    }

    @Test
    void classifiesEmptyModelOutputAndKeepsLegacyProtocolMessageReadable() {
        assertThat(AgentRunFailureClassifier.classify(
                new RuntimeException("model returned an empty response")).code())
                .isEqualTo("MODEL_EMPTY_RESPONSE");
        assertThat(AgentRunFailureClassifier.classify(
                new RuntimeException("MODEL_EMPTY_RESPONSE: 模型返回了空响应"))
                .category()).isEqualTo(AgentRunFailureClassifier.PROVIDER_TRANSIENT);
        assertThat(AgentRunFailureClassifier.userMessage("MODEL_EMPTY_RESPONSE"))
                .isEqualTo("模型未返回有效内容，请重试");
        assertThat(AgentRunFailureClassifier.userMessage("ANSWER_SUBMISSION_REQUIRED"))
                .isEqualTo("模型未通过结构化答案提交，请重试");
        assertThat(AgentRunFailureClassifier.userMessage("AGENT_PROTOCOL_ERROR"))
                .isEqualTo("模型返回格式不符合要求，请重试");
    }

    @Test
    void distinguishesQueueTimeoutFromModelTimeout() {
        assertThat(AgentRunFailureClassifier.userMessage("QUEUE_TIMEOUT"))
                .contains("排队时间过长");
        assertThat(AgentRunFailureClassifier.userMessage("RUN_TIMEOUT"))
                .contains("模型响应超时");
    }

    @Test
    void exposesRetryPolicyWithoutTreatingProjectGuardsAsProviderBusy() {
        assertThat(AgentRunFailureClassifier.fromCode("MODEL_OVERLOADED"))
                .extracting(AgentRunFailureClassifier.Failure::category,
                        AgentRunFailureClassifier.Failure::retryable)
                .containsExactly(AgentRunFailureClassifier.PROVIDER_TRANSIENT, true);
        assertThat(AgentRunFailureClassifier.fromCode("CONTEXT_BUDGET_EXCEEDED"))
                .extracting(AgentRunFailureClassifier.Failure::category,
                        AgentRunFailureClassifier.Failure::retryable)
                .containsExactly(AgentRunFailureClassifier.PROJECT_LIMIT, false);
        assertThat(AgentRunFailureClassifier.fromCode("AGENT_CALL_LIMIT"))
                .extracting(AgentRunFailureClassifier.Failure::category,
                        AgentRunFailureClassifier.Failure::retryable)
                .containsExactly(AgentRunFailureClassifier.PROJECT_LIMIT, false);
    }
}
