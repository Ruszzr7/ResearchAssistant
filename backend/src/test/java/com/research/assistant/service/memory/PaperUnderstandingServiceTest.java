package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private ThreadPoolTaskExecutor executor;
    private PaperUnderstandingService service;
    private PaperMemoryRecord record;
    private PaperMemoryChunk chunkOne;
    private PaperMemoryChunk chunkTwo;

    @BeforeEach
    void setUp() {
        memoryService = mock(PaperMemoryService.class);
        memoryMapper = mock(PaperMemoryMapper.class);
        artifactService = mock(PaperLayoutArtifactService.class);
        chunker = mock(PaperMemoryChunker.class);
        modelService = mock(PaperMemoryModelService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.initialize();
        service = new PaperUnderstandingService(
                memoryService, memoryMapper, artifactService, chunker,
                modelService, objectMapper, executor);

        PaperStructure structure = structure();
        PaperMemoryState state = new PaperMemoryState(
                71L, 7L, "a".repeat(64), "parser", PaperStructure.SCHEMA_VERSION,
                PaperMemoryService.STATUS_STRUCTURED, 1, structure, "",
                Instant.now(), Instant.now());
        record = record();
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                7L, "a".repeat(64), "parser", 0.9, Instant.now(), 2, List.of());
        chunkOne = chunk("pmc-1", 1, "b-1");
        chunkTwo = chunk("pmc-2", 2, "b-2");

        when(memoryService.ensureStructure(7L, false)).thenReturn(state);
        when(memoryMapper.selectVersion(
                7L, "a".repeat(64), "parser", PaperStructure.SCHEMA_VERSION)).thenReturn(record);
        when(artifactService.ensureArtifact(7L, false)).thenReturn(artifact);
        when(chunker.chunk(structure, artifact)).thenReturn(List.of(chunkOne, chunkTwo));
        when(memoryMapper.updateById(any(PaperMemoryRecord.class))).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void shouldSummarizeChunksInParallelCheckpointAndBuildReadyProfile() {
        PaperChunkSummary first = summary(chunkOne, 11, 5);
        PaperChunkSummary second = summary(chunkTwo, 13, 6);
        PaperGlobalProfile profile = profile(2, 2, 0, true);
        when(modelService.summarize(chunkOne)).thenReturn(first);
        when(modelService.summarize(chunkTwo)).thenReturn(second);
        when(modelService.profile(any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(new PaperMemoryModelService.ProfileGeneration(profile, 20, 9));
        List<String> stages = new ArrayList<>();

        PaperUnderstandingResult result = service.understand(7L, false, stages::add);

        assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
        assertThat(result.completedChunks()).isEqualTo(2);
        assertThat(result.failedChunks()).isZero();
        assertThat(result.promptTokens()).isEqualTo(44);
        assertThat(result.completionTokens()).isEqualTo(20);
        assertThat(result.profile()).isEqualTo(profile);
        assertThat(record.getChunkSummariesJson()).contains("pmc-1", "pmc-2");
        assertThat(record.getProfileJson()).contains("paper-profile-v1");
        assertThat(record.getUnderstandingCompletedAt()).isNotNull();
        assertThat(stages).anyMatch(stage -> stage.contains("全局画像"))
                .endsWith("论文记忆已就绪");
    }

    @Test
    void shouldResumeReadyChunksAndRetryOnlyMissingChunk() throws Exception {
        PaperChunkSummary first = summary(chunkOne, 11, 5);
        PaperChunkSummary second = summary(chunkTwo, 13, 6);
        record.setStatus(PaperUnderstandingService.STATUS_PARTIAL);
        record.setUnderstandingVersion(PaperUnderstandingService.PIPELINE_VERSION);
        record.setChunkSummariesJson(objectMapper.writeValueAsString(List.of(first)));
        when(modelService.summarize(chunkTwo)).thenReturn(second);
        when(modelService.profile(any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(new PaperMemoryModelService.ProfileGeneration(
                        profile(2, 2, 0, true), 10, 4));

        PaperUnderstandingResult result = service.understand(7L, false, ignored -> { });

        assertThat(result.status()).isEqualTo(PaperUnderstandingService.STATUS_READY);
        verify(modelService, never()).summarize(chunkOne);
        verify(modelService).summarize(chunkTwo);
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

    private PaperMemoryChunk chunk(String id, int ordinal, String blockId) {
        return new PaperMemoryChunk(
                id, ("f" + ordinal).repeat(32), ordinal, "section-1", List.of("Method"),
                ordinal, ordinal, List.of(blockId), "[" + blockId + "] text");
    }

    private PaperChunkSummary summary(PaperMemoryChunk chunk, int promptTokens, int completionTokens) {
        return new PaperChunkSummary(
                chunk.id(), chunk.sourceFingerprint(), chunk.ordinal(), chunk.sectionId(),
                chunk.headingPath(), chunk.pageStart(), chunk.pageEnd(), chunk.blockIds(),
                "Summary " + chunk.ordinal(),
                List.of(new PaperMemoryClaim("METHOD", "Method claim", chunk.blockIds(), 0.9)),
                List.of(), List.of(), List.of(), List.of(), PaperChunkSummary.READY,
                List.of(), promptTokens, completionTokens, "STOP", Instant.now());
    }

    private PaperGlobalProfile profile(int total, int ready, int failed, boolean complete) {
        return new PaperGlobalProfile(
                PaperGlobalProfile.SCHEMA_VERSION, 7L, "Paper", "AI", "Problem",
                List.of(new PaperMemoryClaim("CONTRIBUTION", "Contribution", List.of("b-1"), 0.9)),
                "EXPERIMENTAL", "Method", List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                new PaperGlobalProfile.Coverage(total, ready, failed, complete),
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
