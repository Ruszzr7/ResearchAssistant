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
    void shouldBuildOneCompletePaperInputWithoutModelChunking() {
        Paper paper = new Paper();
        paper.setId(31L);
        paper.setTitle("Whole paper");
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(
                31L, "e".repeat(64), "parser+semantic", 0.9,
                Instant.parse("2026-07-19T00:00:00Z"), 2,
                List.of(
                        block("title", 1, 0, DocumentBlockRole.TITLE, "Whole paper"),
                        block("b1", 1, 1, DocumentBlockRole.BODY, "A".repeat(2_000)),
                        block("eq21", 2, 2, DocumentBlockRole.FORMULA, "(21)"),
                        block("b2", 2, 3, DocumentBlockRole.BODY, "B".repeat(2_000)),
                        block("ref", 2, 4, DocumentBlockRole.REFERENCE, "[1] ignored")));
        PaperStructure structure = new PaperStructureBuilder(
                new ObjectMapper().findAndRegisterModules()).build(paper, artifact);

        PaperMemoryChunk whole = new PaperMemoryChunker(1_200)
                .wholePaper(structure, artifact);

        assertThat(whole.ordinal()).isEqualTo(1);
        assertThat(whole.sectionId()).isEqualTo("whole-paper");
        assertThat(whole.blockIds()).containsExactly("b1", "eq21", "b2");
        assertThat(whole.characterCount()).isGreaterThan(4_000);
        assertThat(whole.text()).contains("[b1 | page 1 | BODY]")
                .contains("[eq21 | page 2 | FORMULA]")
                .contains("[b2 | page 2 | BODY]");
    }

    private DocumentBlock block(String id, int page, int order,
                                DocumentBlockRole role, String text) {
        return new DocumentBlock(
                id, page, new NormalizedBoundingBox(0.1, 0.1, 0.7, 0.05),
                role, order, List.of(), text, null, null, 0.9);
    }
}
