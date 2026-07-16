package com.research.assistant.service.pdf.formula.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormulaVisionRecognizerTest {

    private final LLMService llmService = mock(LLMService.class);
    private final FormulaVisionRecognizer recognizer = new FormulaVisionRecognizer(
            llmService, new ObjectMapper());

    @Test
    void parsesJsonAndRemovesDisplayMathDelimiters() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.of("```json\n{\"latex\":\"$$\\\\sum_{k=1}^n x_k$$\",\"confidence\":0.91}\n```"));

        FormulaVisionRecognizer.FormulaCandidate result = recognizer.recognize(new byte[]{1});

        assertThat(result.latex()).isEqualTo("\\sum_{k=1}^n x_k");
        assertThat(result.confidence()).isEqualTo(0.91);
    }

    @Test
    void rejectsNonJsonOutputInsteadOfTreatingItAsEvidence() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.of("probably x squared"));

        assertThatThrownBy(() -> recognizer.recognize(new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
