package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperAnalysis;
import com.research.assistant.mapper.PaperAnalysisMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperAnalysisProjectionServiceTest {

    @Test
    void shouldProjectGlobalMemoryWithoutAnotherModelCall() {
        PaperAnalysisMapper analysisMapper = mock(PaperAnalysisMapper.class);
        PaperMemoryService memoryService = mock(PaperMemoryService.class);
        PaperLayoutArtifactService artifactService = mock(PaperLayoutArtifactService.class);
        PaperAnalysisProjectionService service = new PaperAnalysisProjectionService(
                analysisMapper, memoryService, artifactService,
                new ObjectMapper().findAndRegisterModules());
        PaperStructure structure = structure();
        PaperMemoryState state = new PaperMemoryState(
                12L, 7L, "a".repeat(64), "parser", PaperStructure.SCHEMA_VERSION,
                PaperUnderstandingService.STATUS_READY, 2, structure, "",
                Instant.now(), Instant.now());
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser", 0.9, Instant.now(), 1,
                List.of(new DocumentBlock(
                        "b-1", 1, new NormalizedBoundingBox(0.1, 0.2, 0.7, 0.1),
                        DocumentBlockRole.BODY, 1, List.of("Method"), "Source body text.",
                        null, null, 0.9)));
        PaperGlobalProfile profile = profile();
        PaperUnderstandingResult understanding = new PaperUnderstandingResult(
                12L, 7L, PaperUnderstandingService.STATUS_READY,
                1, 1, 0, 30, 10, List.of(), profile);
        when(memoryService.latestStructure(7L)).thenReturn(state);
        when(artifactService.latestArtifact(7L)).thenReturn(artifact);

        PaperAnalysis projected = service.project(7L, understanding);

        assertThat(projected.getCoreContribution()).isEqualTo("Contribution");
        assertThat(projected.getMethodType()).isEqualTo("EXPERIMENTAL");
        assertThat(projected.getDatasetsJson()).contains("Dataset D");
        assertThat(projected.getRawText()).contains("Source body text");
        assertThat(projected.getTokenUsed()).isEqualTo(40);
        ArgumentCaptor<PaperAnalysis> captor = ArgumentCaptor.forClass(PaperAnalysis.class);
        verify(analysisMapper).insert(captor.capture());
        assertThat(captor.getValue().getLayoutDocumentHash()).isEqualTo("a".repeat(64));
    }

    private PaperGlobalProfile profile() {
        return new PaperGlobalProfile(
                PaperGlobalProfile.SCHEMA_VERSION, 7L, "Paper", "AI", "Problem",
                List.of(new PaperMemoryClaim(
                        "CONTRIBUTION", "Contribution", List.of("b-1"), 0.9)),
                "EXPERIMENTAL", "Method summary", List.of("Dataset D"),
                List.of("Model M"), List.of("Accuracy"),
                List.of(new PaperMemoryClaim("FINDING", "Finding", List.of("b-1"), 0.8)),
                List.of(), Map.of("taskDefinition", "Task"), List.of(), List.of(),
                List.of(), new PaperGlobalProfile.Coverage(1, 1, 0, true),
                List.of(), Instant.now());
    }

    private PaperStructure structure() {
        return new PaperStructure(
                PaperStructure.SCHEMA_VERSION, 7L,
                new PaperStructure.Source("a".repeat(64), "parser", 0.9, "parser"),
                new PaperStructure.Metadata("Paper", List.of(), 2026, "", "", List.of(), ""),
                1, List.of("b-1"), List.of(), List.of(), List.of(),
                new PaperStructure.Statistics(1, 1, 0, 10, Map.of(), Map.of()),
                new PaperStructure.Quality(0.9, 0.9, 0, 0, List.of()), Instant.now());
    }
}
