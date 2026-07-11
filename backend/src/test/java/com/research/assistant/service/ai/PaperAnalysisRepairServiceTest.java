package com.research.assistant.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaperAnalysisRepairServiceTest {

    private final LLMService llmService = mock(LLMService.class);
    private final PaperAnalysisQualityGate qualityGate = new PaperAnalysisQualityGate();
    private final PaperAnalysisRepairService service = new PaperAnalysisRepairService(
            llmService, new ObjectMapper(), qualityGate);

    @Test
    void shouldRepairInvalidResultAtMostOnce() {
        PaperAnalysisResult invalid = new PaperAnalysisResult();
        invalid.setDomain("AI");
        invalid.setCoreContribution("");
        invalid.setMethodType("UNKNOWN");
        invalid.setMethodSummary("");
        PaperAnalysisQualityGate.QualityReport initial = qualityGate.validateAndRepair(invalid, "vision");

        when(llmService.chatWithUsage(any(), any())).thenReturn(new LlmResponse("""
                {"domain":"AI","coreContribution":"contribution","methodType":"SYSTEM","methodSummary":"summary"}
                """, 10, 20, 30));

        PaperAnalysisRepairService.RepairAttempt attempt = service.repair(invalid, initial, "vision");

        assertThat(attempt.succeeded()).isTrue();
        assertThat(attempt.attempts()).isEqualTo(1);
        assertThat(attempt.result().getMethodType()).isEqualTo("SYSTEM");
        assertThat(attempt.response().getTotalTokens()).isEqualTo(30);
        verify(llmService, times(1)).chatWithUsage(any(), any());
    }

    @Test
    void shouldRejectRepairOutputOverBudget() {
        PaperAnalysisResult invalid = new PaperAnalysisResult();
        PaperAnalysisQualityGate.QualityReport initial = qualityGate.validateAndRepair(invalid, "topic");
        when(llmService.chatWithUsage(any(), any()))
                .thenReturn(new LlmResponse("{}", 10, 2049, 2059));

        PaperAnalysisRepairService.RepairAttempt attempt = service.repair(invalid, initial, "topic");

        assertThat(attempt.succeeded()).isFalse();
        assertThat(attempt.error()).contains("budget");
        verify(llmService, times(1)).chatWithUsage(any(), any());
    }

    @Test
    void shouldNotCallModelWhenResultIsAlreadyValid() {
        PaperAnalysisResult valid = new PaperAnalysisResult();
        valid.setDomain("AI");
        valid.setCoreContribution("contribution");
        valid.setMethodType("SYSTEM");
        valid.setMethodSummary("summary");
        PaperAnalysisQualityGate.QualityReport quality = qualityGate.validateAndRepair(valid, "topic");

        PaperAnalysisRepairService.RepairAttempt attempt = service.repair(valid, quality, "topic");

        assertThat(attempt.attempts()).isZero();
        verifyNoInteractions(llmService);
    }
}
