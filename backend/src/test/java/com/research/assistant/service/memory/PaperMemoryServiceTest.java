package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperMemoryServiceTest {

    @Mock private PaperMapper paperMapper;
    @Mock private PaperMemoryMapper memoryMapper;
    @Mock private PaperLayoutArtifactService layoutArtifactService;
    @Mock private PaperStructureBuilder structureBuilder;

    private ObjectMapper objectMapper;
    private PaperMemoryService service;
    private Paper paper;
    private PaperLayoutArtifact artifact;
    private PaperStructure structure;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PaperMemoryService(
                paperMapper, memoryMapper, layoutArtifactService, structureBuilder, objectMapper);
        paper = new Paper();
        paper.setId(9L);
        paper.setTitle("Memory paper");
        artifact = new PaperLayoutArtifact(
                9L, "b".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-19T00:00:00Z"), 1,
                List.of(new DocumentBlock(
                        "body", 1, new NormalizedBoundingBox(0.1, 0.2, 0.7, 0.1),
                        DocumentBlockRole.BODY, 0, List.of(), "Body text.", null, null, 0.9)));
        structure = new PaperStructureBuilder(objectMapper).build(paper, artifact);
        when(paperMapper.selectById(9L)).thenReturn(paper);
        when(layoutArtifactService.ensureArtifact(9L, false)).thenReturn(artifact);
    }

    @Test
    void shouldReturnMatchingCachedStructureWithoutRebuilding() throws Exception {
        PaperMemoryRecord cached = record(44L, objectMapper.writeValueAsString(structure));
        when(memoryMapper.selectVersion(
                9L, "b".repeat(64), "parser+semantic", PaperStructure.SCHEMA_VERSION))
                .thenReturn(cached);

        PaperMemoryState state = service.ensureStructure(9L, false);

        assertThat(state.id()).isEqualTo(44L);
        assertThat(state.structure().readingOrder()).containsExactly("body");
        verify(structureBuilder, never()).build(any(), any());
        verify(memoryMapper, never()).insert(any(PaperMemoryRecord.class));
    }

    @Test
    void shouldBuildAndPersistVersionedStructureWhenCacheIsMissing() {
        when(memoryMapper.selectVersion(
                9L, "b".repeat(64), "parser+semantic", PaperStructure.SCHEMA_VERSION))
                .thenReturn(null);
        when(structureBuilder.build(paper, artifact)).thenReturn(structure);
        when(memoryMapper.insert(any(PaperMemoryRecord.class))).thenAnswer(invocation -> {
            PaperMemoryRecord inserted = invocation.getArgument(0);
            inserted.setId(45L);
            return 1;
        });

        PaperMemoryState state = service.ensureStructure(9L, false);

        assertThat(state.status()).isEqualTo(PaperMemoryService.STATUS_STRUCTURED);
        assertThat(state.revision()).isEqualTo(1);
        ArgumentCaptor<PaperMemoryRecord> captor = ArgumentCaptor.forClass(PaperMemoryRecord.class);
        verify(memoryMapper).insert(captor.capture());
        PaperMemoryRecord inserted = captor.getValue();
        assertThat(inserted.getDocumentHash()).isEqualTo("b".repeat(64));
        assertThat(inserted.getLayoutParserVersion()).isEqualTo("parser+semantic");
        assertThat(inserted.getStructureJson()).contains(PaperStructure.SCHEMA_VERSION, "Memory paper", "body");
        assertThat(inserted.getChunkSummariesJson()).isNull();
        assertThat(inserted.getProfileJson()).isNull();
        assertThat(inserted.getUnderstandingVersion()).isNull();
        assertThat(inserted.getStageText()).isEqualTo("PDF 结构已就绪");
        assertThat(inserted.getTotalChunks()).isZero();
    }

    @Test
    void shouldRebuildCorruptCachedStructureAndIncrementRevision() {
        PaperMemoryRecord cached = record(46L, "not-json");
        cached.setRevision(3);
        when(memoryMapper.selectVersion(
                9L, "b".repeat(64), "parser+semantic", PaperStructure.SCHEMA_VERSION))
                .thenReturn(cached);
        when(structureBuilder.build(paper, artifact)).thenReturn(structure);

        PaperMemoryState state = service.ensureStructure(9L, false);

        assertThat(state.revision()).isEqualTo(4);
        verify(memoryMapper).updateById(cached);
    }

    @Test
    void shouldRebuildParseableCacheThatInventsABlockIdentity() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(objectMapper.writeValueAsString(structure));
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("readingOrder")).add("invented-block");
        PaperMemoryRecord cached = record(47L, objectMapper.writeValueAsString(root));
        cached.setRevision(2);
        when(memoryMapper.selectVersion(
                9L, "b".repeat(64), "parser+semantic", PaperStructure.SCHEMA_VERSION))
                .thenReturn(cached);
        when(structureBuilder.build(paper, artifact)).thenReturn(structure);

        PaperMemoryState state = service.ensureStructure(9L, false);

        assertThat(state.revision()).isEqualTo(3);
        verify(memoryMapper).updateById(cached);
    }

    private PaperMemoryRecord record(Long id, String structureJson) {
        PaperMemoryRecord record = new PaperMemoryRecord();
        record.setId(id);
        record.setPaperId(9L);
        record.setDocumentHash("b".repeat(64));
        record.setLayoutParserVersion("parser+semantic");
        record.setSchemaVersion(PaperStructure.SCHEMA_VERSION);
        record.setStatus(PaperMemoryService.STATUS_STRUCTURED);
        record.setStructureJson(structureJson);
        record.setRevision(1);
        record.setGeneratedAt(LocalDateTime.ofInstant(
                Instant.parse("2026-07-19T00:00:00Z"), ZoneId.systemDefault()));
        record.setUpdatedAt(LocalDateTime.now());
        return record;
    }
}
