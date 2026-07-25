package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.LlmResponse;
import com.research.assistant.service.LLMService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperMemoryModelServiceTest {

    private LLMService llmService;
    private PaperMemoryModelService service;

    @BeforeEach
    void setUp() {
        llmService = mock(LLMService.class);
        service = new PaperMemoryModelService(
                llmService, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldRetryInvalidJsonAndDropClaimsOutsideBlockWhitelist() {
        PaperMemoryChunk chunk = chunk();
        String valid = """
                {
                  "synopsis":"The section introduces a bounded estimator.",
                  "claims":[
                    {"category":"METHOD","statement":"The paper uses estimator E.",
                     "evidenceBlockIds":["b-1"],"confidence":0.91},
                    {"category":"FINDING","statement":"Unsupported result.",
                     "evidenceBlockIds":["foreign-block"],"confidence":0.99}
                  ],
                  "concepts":["estimator"],"datasets":[],"models":["E"],"metrics":[]
                }
                """;
        when(llmService.chatWithUsage(anyString(), anyString(), any()))
                .thenReturn(new LlmResponse("not json", 2, 1, 3, "STOP"))
                .thenReturn(new LlmResponse(valid, 20, 10, 30, "STOP"));

        PaperChunkSummary summary = service.summarize(chunk);

        assertThat(summary.ready()).isTrue();
        assertThat(summary.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.category()).isEqualTo("METHOD");
            assertThat(claim.evidenceBlockIds()).containsExactly("b-1");
        });
        assertThat(summary.qualityIssues()).contains("UNGROUNDED_CLAIM_DROPPED");
        assertThat(summary.promptTokens()).isEqualTo(22);
        assertThat(summary.completionTokens()).isEqualTo(11);
        verify(llmService, times(2)).chatWithUsage(anyString(), anyString(), any());
    }

    @Test
    void shouldUnderstandWholePaperWithOneMainCall() {
        String profileJson = """
                {
                  "domain":"AI", "researchProblem":"Estimate the target robustly.",
                  "coreContributions":[
                    {"category":"CONTRIBUTION","statement":"Introduces estimator E.",
                     "evidenceBlockIds":["b-1"],"confidence":0.88}
                  ],
                  "methodType":"EXPERIMENTAL", "methodSummary":"Estimator E is evaluated.",
                  "datasets":[],"models":["E"],"metrics":[],
                  "keyFindings":[],"limitations":[],
                  "experimentSetup":{},"benchmarkResults":[],
                  "sectionDigests":[],"openQuestions":[]
                }
                """;
        when(llmService.chatWithUsage(anyString(), anyString(), any()))
                .thenReturn(new LlmResponse(profileJson, 500, 180, 680, "STOP"));

        PaperMemoryModelService.WholePaperGeneration generated =
                service.understandWhole(structure(), chunk());

        assertThat(generated.profile().researchProblem()).contains("Estimate");
        assertThat(generated.summary().promptTokens()).isEqualTo(500);
        assertThat(generated.summary().completionTokens()).isEqualTo(180);
        verify(llmService).chatWithUsage(anyString(), anyString(), any());
    }

    @Test
    void shouldNotSpendARepairCallOnLengthTruncatedOutput() {
        when(llmService.chatWithUsage(anyString(), anyString(), any()))
                .thenReturn(new LlmResponse("{\"synopsis\":\"cut", 700, 1_200, 1_900, "LENGTH"));

        assertThatThrownBy(() -> service.summarize(chunk()))
                .isInstanceOf(PaperMemoryGenerationException.class)
                .satisfies(error -> {
                    PaperMemoryGenerationException generation =
                            (PaperMemoryGenerationException) error;
                    assertThat(generation.promptTokens()).isEqualTo(700);
                    assertThat(generation.completionTokens()).isEqualTo(1_200);
                    assertThat(generation.finishReason()).isEqualTo("LENGTH");
                });
        verify(llmService).chatWithUsage(anyString(), anyString(), any());
    }

    @Test
    void shouldBuildGlobalProfileWithDeterministicCoverageAndGroundedClaims() {
        PaperChunkSummary summary = new PaperChunkSummary(
                "pmc-1", "f".repeat(64), 1, "section-1", List.of("Method"),
                1, 2, List.of("b-1"), "A method summary.",
                List.of(new PaperMemoryClaim("METHOD", "Uses E.", List.of("b-1"), 0.9)),
                List.of(), List.of("D"), List.of("E"), List.of("accuracy"),
                PaperChunkSummary.READY, List.of(), 12, 7, "STOP", Instant.now());
        String profileJson = """
                {
                  "domain":"AI", "researchProblem":"Estimate the target robustly.",
                  "coreContributions":[
                    {"category":"CONTRIBUTION","statement":"Introduces estimator E.",
                     "evidenceBlockIds":["b-1"],"confidence":0.88}
                  ],
                  "methodType":"EXPERIMENTAL", "methodSummary":"Estimator E is evaluated.",
                  "datasets":["D"],"models":["E"],"metrics":["accuracy"],
                  "keyFindings":[],"limitations":[],
                  "experimentSetup":{"taskDefinition":"estimation"},
                  "benchmarkResults":[],
                  "sectionDigests":[{"sectionId":"section-1","headingPath":["Method"],
                    "summary":"Method overview.","sourceChunkIds":["pmc-1"]}],
                  "openQuestions":[]
                }
                """;
        when(llmService.chatWithUsage(anyString(), anyString(), any()))
                .thenReturn(new LlmResponse(profileJson, 40, 18, 58, "STOP"));

        PaperMemoryModelService.ProfileGeneration generated = service.profile(
                structure(), List.of(summary), 2, 1);

        assertThat(generated.profile().domain()).isEqualTo("AI");
        assertThat(generated.profile().coreContributions()).singleElement()
                .extracting(PaperMemoryClaim::evidenceBlockIds)
                .isEqualTo(List.of("b-1"));
        assertThat(generated.profile().coverage().totalChunks()).isEqualTo(2);
        assertThat(generated.profile().coverage().summarizedChunks()).isEqualTo(1);
        assertThat(generated.profile().coverage().complete()).isFalse();
        assertThat(generated.promptTokens()).isEqualTo(40);
    }

    private PaperMemoryChunk chunk() {
        return new PaperMemoryChunk(
                "pmc-1", "f".repeat(64), 1, "section-1", List.of("Method"),
                1, 1, List.of("b-1"), "[b-1 | page 1 | BODY] source text");
    }

    private PaperStructure structure() {
        return new PaperStructure(
                PaperStructure.SCHEMA_VERSION, 17L,
                new PaperStructure.Source("a".repeat(64), "parser", 0.9, "parser"),
                new PaperStructure.Metadata("Paper", List.of(), 2026, "", "", List.of(), ""),
                2, List.of("b-1"), List.of(), List.of(), List.of(),
                new PaperStructure.Statistics(1, 1, 0, 11, Map.of(), Map.of()),
                new PaperStructure.Quality(0.9, 0.9, 0, 0, List.of()), Instant.now());
    }
}
