package com.research.assistant.service.agent.document;

import com.research.assistant.dto.agent.AiCapabilityView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentInputPlannerTest {
    private final DocumentInputPlanner planner = new DocumentInputPlanner();

    @Test
    void usesNativePdfOnlyWhenConnectionProbeVerifiedIt() {
        assertThat(planner.plan(new DocumentInputRequest(true, false, 20_000, 10), capability(true, true)).primaryMode())
                .isEqualTo(DocumentInputMode.NATIVE_PDF);
        assertThat(planner.plan(new DocumentInputRequest(true, false, 20_000, 10), capability(true, false)).primaryMode())
                .isEqualTo(DocumentInputMode.STRUCTURED_TEXT);
    }

    @Test
    void visualQuestionsAlwaysUseVerifiedImageInput() {
        assertThat(planner.plan(new DocumentInputRequest(false, true, 5_000, 1), capability(true, false)).primaryMode())
                .isEqualTo(DocumentInputMode.PAGE_IMAGES);
        assertThatThrownBy(() -> planner.plan(new DocumentInputRequest(false, true, 5_000, 1), capability(false, true)))
                .hasMessageContaining("IMAGE_CAPABILITY");
    }

    @Test
    void longTextUsesHierarchicalReadWithoutRag() {
        assertThat(planner.plan(new DocumentInputRequest(true, false, 300_000, 40), capability(true, false)).primaryMode())
                .isEqualTo(DocumentInputMode.HIERARCHICAL_READ);
    }

    private AiCapabilityView capability(boolean image, boolean pdf) {
        return new AiCapabilityView("VERIFIED", true, true, true, image, true,
                image, pdf, null, null, java.time.LocalDateTime.now(), java.time.LocalDateTime.now().plusDays(1));
    }
}
