package com.research.assistant.service.pdf.formula.region;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.LlmCallPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
        ArgumentCaptor<LlmCallPolicy> policy = ArgumentCaptor.forClass(LlmCallPolicy.class);
        verify(llmService).chatWithImageUsage(any(), any(), any(), any(), policy.capture());
        assertThat(policy.getValue().maxOutputTokens()).isEqualTo(768);
        assertThat(policy.getValue().reasoningEffort()).isEqualTo("low");
    }

    @Test
    void returnsAllDistinctFormulasInVisualOrder() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.of("""
                        {"formulas":[
                          {"latex":"R_c=C(\\\\Gamma_c)","confidence":0.92},
                          {"latex":"R_{p,k}=C(\\\\Gamma_{p,k})","confidence":0.95}
                        ],"confidence":0.93}
                        """));

        FormulaVisionRecognizer.FormulaCandidate result = recognizer.recognize(new byte[]{2});

        assertThat(result.formulas()).containsExactly(
                "R_c=C(\\Gamma_c)", "R_{p,k}=C(\\Gamma_{p,k})");
        assertThat(result.latex()).contains("\\begin{gathered}", "R_c", "R_{p,k}");
        assertThat(result.confidence()).isEqualTo(0.93);
    }

    @Test
    void rejectsNonJsonOutputInsteadOfTreatingItAsEvidence() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.of("probably x squared"));

        assertThatThrownBy(() -> recognizer.recognize(new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        verify(llmService, times(2))
                .chatWithImageUsage(any(), any(), any(), any(), any());
    }

    @Test
    void retriesOneMalformedJsonResponseAndKeepsTheValidCandidate() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(
                        LlmResponse.of("{not-json}"),
                        LlmResponse.of("{\"latex\":\"x+y\",\"confidence\":0.93}"));

        FormulaVisionRecognizer.FormulaCandidate result = recognizer.recognize(new byte[]{4});

        assertThat(result.latex()).isEqualTo("x+y");
        assertThat(result.confidence()).isEqualTo(0.93);
        verify(llmService, times(2))
                .chatWithImageUsage(any(), any(), any(), any(), any());
    }

    @Test
    void retriesOneTruncatedOutputWithABoundedLargerBudget() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(
                        new LlmResponse("{\"latex\":\"\\\\sum_", 300, 768, 1_068, "LENGTH"),
                        new LlmResponse("{\"latex\":\"\\\\sum_", 300, 1_536, 1_836, "LENGTH"));

        assertThatThrownBy(() -> recognizer.recognize(new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("截断");
        ArgumentCaptor<LlmCallPolicy> policies = ArgumentCaptor.forClass(LlmCallPolicy.class);
        verify(llmService, times(2))
                .chatWithImageUsage(any(), any(), any(), any(), policies.capture());
        assertThat(policies.getAllValues()).extracting(LlmCallPolicy::maxOutputTokens)
                .containsExactly(768, 1_536);
    }

    @Test
    void reusesAnExactNormalizedImageCandidateWithoutAnotherModelCall() {
        when(llmService.chatWithImageUsage(any(), any(), any(), any(), any()))
                .thenReturn(LlmResponse.of("{\"latex\":\"x+y\",\"confidence\":0.9}"));
        byte[] image = new byte[]{9, 8, 7};

        FormulaVisionRecognizer.FormulaCandidate first = recognizer.recognize(image);
        FormulaVisionRecognizer.FormulaCandidate second = recognizer.recognize(image);

        assertThat(second).isEqualTo(first);
        verify(llmService, times(1)).chatWithImageUsage(any(), any(), any(), any(), any());
    }
}
