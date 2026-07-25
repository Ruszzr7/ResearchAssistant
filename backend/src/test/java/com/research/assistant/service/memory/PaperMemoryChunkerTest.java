package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperMemoryChunkerTest {

    @Test
    void shouldMergeSmallSectionsAndSplitOnlyAtBoundedSizes() {
        Paper paper = new Paper();
        paper.setId(31L);
        paper.setTitle("Chunked paper");
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                31L, "d".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-19T00:00:00Z"), 3,
                List.of(
                        block("title", 1, 0, DocumentBlockRole.TITLE, "Chunked paper"),
                        block("h1", 1, 1, DocumentBlockRole.HEADING, "1 Introduction"),
                        block("b1", 1, 2, DocumentBlockRole.BODY, "A".repeat(700)),
                        block("b2", 2, 3, DocumentBlockRole.BODY, "B".repeat(700)),
                        block("h2", 2, 4, DocumentBlockRole.HEADING, "2 Method"),
                        block("b3", 2, 5, DocumentBlockRole.BODY, "C".repeat(300)),
                        block("rh", 3, 6, DocumentBlockRole.HEADING, "References"),
                        block("ref", 3, 7, DocumentBlockRole.REFERENCE, "[1] cited work")));
        PaperStructure structure = new PaperStructureBuilder(
                new ObjectMapper().findAndRegisterModules()).build(paper, artifact);
        PaperMemoryChunker chunker = new PaperMemoryChunker(1_000, 1_200);

        List<PaperMemoryChunk> first = chunker.chunk(structure, artifact);
        List<PaperMemoryChunk> second = chunker.chunk(structure, artifact);

        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.characterCount()).isLessThanOrEqualTo(1_200);
            assertThat(chunk.sourceFingerprint()).hasSize(64);
            assertThat(chunk.blockIds()).isNotEmpty();
        });
        assertThat(first).extracting(PaperMemoryChunk::id)
                .containsExactlyElementsOf(second.stream().map(PaperMemoryChunk::id).toList());
        assertThat(first.stream().flatMap(chunk -> chunk.blockIds().stream()).toList())
                .contains("h1", "b1", "b2", "h2", "b3")
                .doesNotContain("ref");
        assertThat(first.stream().filter(chunk -> chunk.blockIds().contains("b1")).findFirst().orElseThrow()
                .headingPath()).contains("1 Introduction");
        assertThat(first.stream().filter(chunk -> chunk.blockIds().contains("b3")).findFirst())
                .isPresent();
    }

    @Test
    void shouldUseOneWholePaperChunkWithinSinglePassBudget() {
        Paper paper = new Paper();
        paper.setId(31L);
        paper.setTitle("Whole paper");
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                31L, "d".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-19T00:00:00Z"), 2,
                List.of(
                        block("h1", 1, 0, DocumentBlockRole.HEADING, "1 Introduction"),
                        block("b1", 1, 1, DocumentBlockRole.BODY, "A".repeat(700)),
                        block("h2", 2, 2, DocumentBlockRole.HEADING, "2 Method"),
                        block("b2", 2, 3, DocumentBlockRole.BODY, "B".repeat(700))));
        PaperStructure structure = new PaperStructureBuilder(
                new ObjectMapper().findAndRegisterModules()).build(paper, artifact);

        List<PaperMemoryChunk> chunks =
                new PaperMemoryChunker(1_000, 1_200, 3_000).chunk(structure, artifact);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.sectionId()).isEqualTo("whole-paper");
            assertThat(chunk.blockIds()).containsExactly("h1", "b1", "h2", "b2");
            assertThat(chunk.characterCount()).isGreaterThan(1_200);
        });
    }

    private DocumentBlock block(String id, int page, int order,
                                DocumentBlockRole role, String text) {
        return new DocumentBlock(
                id, page, new NormalizedBoundingBox(0.1, 0.1, 0.7, 0.05),
                role, order, List.of(), text, null, null, 0.9);
    }
}
