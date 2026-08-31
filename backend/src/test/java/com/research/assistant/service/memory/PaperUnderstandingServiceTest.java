package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperUnderstandingServiceTest {

    private PaperMemoryService memoryService;
    private PaperMemoryMapper memoryMapper;
    private PaperLayoutArtifactService artifactService;
    private PaperMemoryChunker chunker;
    private PaperMemoryModelService modelService;
    private ObjectMapper objectMapper;
    private PaperUnderstandingService service;
    private PaperMemoryRecord record;
    private PaperMemoryChunk wholePaper;
    private PaperLayoutArtifact artifact;

    @BeforeEach
    void setUp() {
        memoryService = mock(PaperMemoryService.class);
        memoryMapper = mock(PaperMemoryMapper.class);
        artifactService = mock(PaperLayoutArtifactService.class);
        chunker = mock(PaperMemoryChunker.class);
        modelService = mock(PaperMemoryModelService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        service = new PaperUnderstandingService(
                memoryService, memoryMapper, artifactService, chunker,
                modelService, objectMapper, executor);

        PaperStructure structure = structure();
        PaperMemoryState state = new PaperMemoryState(
                71L, 7L, "a".repeat(64), "parser", PaperStructure.SCHEMA_VERSION,
                PaperMemoryService.STATUS_STRUCTURED, 1, structure, "",
                Instant.now(), Instant.now());
        record = record();
        artifact = new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser", 0.9, Instant.now(), 2, List.of());
        wholePaper = new PaperMemoryChunk(
                "pmc-whole", "f".repeat(64), 1, "whole-paper", List.of(),
                1, 2, List.of("b-1", "b-2"), "[b-1] paper text\n[b-2] more paper text");

        when(memoryService.ensureStructure(7L, false)).thenReturn(state);
        when(memoryMapper.selectVersion(
                7L, "a".repeat(64), "parser", PaperStructure.SCHEMA_VERSION)).thenReturn(record);
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);
        when(chunker.wholePaper(structure, artifact)).thenReturn(wholePaper);
        when(memoryMapper.updateById(any(PaperMemoryRecord.class))).thenReturn(1);
    }

    @Test
    void shouldMakeExactlyOneWholePaperModelCall() {
        PaperGlobalProfile profile = profile();
        PaperChunkSummary summary = summary(500, 180);
        when(modelService.understandWhole(any(PaperStructure.class), any(PaperLayoutArtifact.class)))
                .thenReturn(new PaperMemoryModelService.WholePaperGeneration(profile, summary));

        PaperUnderstandingResult result = service.understand(7L, false, ignored -> { });

        assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
        assertThat(result.totalChunks()).isEqualTo(1);
        assertThat(result.completedChunks()).isEqualTo(1);
        assertThat(result.promptTokens()).isEqualTo(500);
        assertThat(result.completionTokens()).isEqualTo(180);
        assertThat(result.profile()).isEqualTo(profile);
        verify(modelService).understandWhole(any(PaperStructure.class), any(PaperLayoutArtifact.class));
        verify(modelService, never()).summarize(any());
        verify(modelService, never()).profile(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void shouldReuseOnlyCurrentWholePaperVersion() throws Exception {
        PaperGlobalProfile profile = profile();
        record.setStatus(PaperUnderstandingService.STATUS_READY);
        record.setUnderstandingVersion(PaperUnderstandingService.PIPELINE_VERSION);
        record.setProfileJson(objectMapper.writeValueAsString(profile));
        record.setProfileQualityJson("{\"ready\":true}");

        PaperUnderstandingResult result = service.understand(7L, false, ignored -> { });

        assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
        assertThat(result.profile()).isEqualTo(profile);
        verify(modelService, never()).understandWhole(any(PaperStructure.class), any(PaperLayoutArtifact.class));
        verify(chunker, never()).wholePaper(any(), any());
    }

    @Test
    void shouldPersistTheSingleFailedCallWithoutARepairOrChunkRetry() {
        when(modelService.understandWhole(any(PaperStructure.class), any(PaperLayoutArtifact.class)))
                .thenThrow(new PaperMemoryGenerationException(
                        "invalid", new IllegalArgumentException("bad json"),
                        700, 300, "LENGTH"));

        PaperUnderstandingResult result = service.understand(7L, false, ignored -> { });

        assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_FAILED);
        assertThat(result.totalChunks()).isEqualTo(1);
        assertThat(result.promptTokens()).isEqualTo(700);
        assertThat(result.completionTokens()).isEqualTo(300);
        assertThat(result.summaries()).singleElement().satisfies(summary -> {
            assertThat(summary.ready()).isFalse();
            assertThat(summary.finishReason()).isEqualTo("LENGTH");
        });
        verify(modelService).understandWhole(any(PaperStructure.class), any(PaperLayoutArtifact.class));
        verify(modelService, never()).summarize(any());
        verify(modelService, never()).profile(any(), any(), any(Integer.class), any(Integer.class));
    }

    private PaperMemoryRecord record() {
        PaperMemoryRecord value = new PaperMemoryRecord();
        value.setId(71L);
        value.setPaperId(7L);
        value.setDocumentHash("a".repeat(64));
        value.setLayoutParserVersion("parser");
        value.setSchemaVersion(PaperStructure.SCHEMA_VERSION);
        value.setStatus(PaperMemoryService.STATUS_STRUCTURED);
        value.setStructureJson("{}");
        value.setRevision(1);
        value.setGeneratedAt(LocalDateTime.now());
        value.setCreatedAt(LocalDateTime.now());
        value.setUpdatedAt(LocalDateTime.now());
        return value;
    }

    private PaperChunkSummary summary(int promptTokens, int completionTokens) {
        return new PaperChunkSummary(
                wholePaper.id(), wholePaper.sourceFingerprint(), 1, wholePaper.sectionId(),
                wholePaper.headingPath(), wholePaper.pageStart(), wholePaper.pageEnd(), wholePaper.blockIds(),
                "Whole paper summary",
                List.of(new PaperMemoryClaim("CONTRIBUTION", "Contribution", List.of("b-1"), 0.9)),
                List.of(), List.of(), List.of(), List.of(), PaperChunkSummary.READY,
                List.of(), promptTokens, completionTokens, "STOP", Instant.now());
    }

    private PaperGlobalProfile profile() {
        return new PaperGlobalProfile(
                PaperGlobalProfile.SCHEMA_VERSION, 7L, "Paper", "AI", "Problem",
                List.of(new PaperMemoryClaim("CONTRIBUTION", "Contribution", List.of("b-1"), 0.9)),
                "EXPERIMENTAL", "Method", List.of(), List.of(), List.of(),
                List.of(new PaperMemoryClaim("FINDING", "Finding", List.of("b-2"), 0.8)),
                List.of(), Map.of(), List.of(), List.of(), List.of(),
                new PaperGlobalProfile.Coverage(1, 1, 0, true),
                List.of(), Instant.now());
    }

    private PaperStructure structure() {
        return new PaperStructure(
                PaperStructure.SCHEMA_VERSION, 7L,
                new PaperStructure.Source("a".repeat(64), "parser", 0.9, "parser"),
                new PaperStructure.Metadata("Paper", List.of(), 2026, "", "", List.of(), ""),
                2, List.of("b-1", "b-2"), List.of(), List.of(), List.of(),
                new PaperStructure.Statistics(2, 2, 0, 20, Map.of(), Map.of()),
                new PaperStructure.Quality(0.9, 0.9, 0, 0, List.of()), Instant.now());
    }
}
