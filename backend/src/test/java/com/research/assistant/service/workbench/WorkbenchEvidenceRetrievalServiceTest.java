package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkbenchEvidenceRetrievalServiceTest {

    private WorkbenchEvidenceRetrievalService service;

    @BeforeEach
    void setUp() {
        PaperLayoutEvidencePolicy policy = new PaperLayoutEvidencePolicy();
        PaperLayoutEvidenceService projection = new PaperLayoutEvidenceService(
                policy, mock(SelectionAnchorResolver.class));
        service = new WorkbenchEvidenceRetrievalService(policy, projection);
    }

    @Test
    void samplesAcrossSectionsAndExcludesDecorativeOrReferenceBlocks() {
        PaperLayoutArtifact artifact = artifact(7L, List.of(
                block("header", DocumentBlockRole.HEADER, 0, List.of(), "IEEE header"),
                block("abstract", DocumentBlockRole.ABSTRACT, 1, List.of(), "Low latency abstract"),
                block("h-intro", DocumentBlockRole.HEADING, 2, List.of("Introduction"), "I. Introduction"),
                block("intro", DocumentBlockRole.BODY, 3, List.of("Introduction"), "Motivation for low latency"),
                block("h-method", DocumentBlockRole.HEADING, 4, List.of("Method"), "II. Method"),
                block("method", DocumentBlockRole.BODY, 5, List.of("Method"), "Finite blocklength method"),
                block("results", DocumentBlockRole.BODY, 6, List.of("Results"), "Latency result improves"),
                block("reference", DocumentBlockRole.REFERENCE, 7, List.of("References"), "[1] unrelated"),
                block("footer", DocumentBlockRole.FOOTER, 8, List.of(), "page 1")));

        List<LayoutEvidence> result = service.retrievePaper(artifact, "latency method", 6, 8_000);

        assertThat(result).hasSize(6);
        assertThat(result).extracting(LayoutEvidence::blockId)
                .contains("abstract", "intro", "method", "results")
                .doesNotContain("header", "reference", "footer");
        assertThat(result).extracting(LayoutEvidence::role)
                .allMatch(role -> role != DocumentBlockRole.HEADER
                        && role != DocumentBlockRole.FOOTER
                        && role != DocumentBlockRole.REFERENCE);
    }

    @Test
    void comparisonKeepsEvidenceFromEveryPaper() {
        PaperLayoutArtifact first = artifact(7L, List.of(
                block("p7-a", DocumentBlockRole.ABSTRACT, 1, List.of(), "Paper seven abstract"),
                block("p7-b", DocumentBlockRole.BODY, 2, List.of("Method"), "Paper seven method")));
        PaperLayoutArtifact second = artifact(8L, List.of(
                block("p8-a", DocumentBlockRole.ABSTRACT, 1, List.of(), "Paper eight abstract"),
                block("p8-b", DocumentBlockRole.BODY, 2, List.of("Method"), "Paper eight method")));

        List<LayoutEvidence> result = service.retrieveComparison(
                List.of(first, second), "compare method", 8, 8_000);

        assertThat(result).extracting(LayoutEvidence::paperId).contains(7L, 8L);
        assertThat(result.stream().filter(item -> item.paperId().equals(7L))).isNotEmpty();
        assertThat(result.stream().filter(item -> item.paperId().equals(8L))).isNotEmpty();
    }

    private PaperLayoutArtifact artifact(Long paperId, List<DocumentBlock> blocks) {
        return new PaperLayoutArtifact(paperId, hash(paperId), "parser-v1", 0.9,
                Instant.parse("2026-07-16T00:00:00Z"), 2, blocks);
    }

    private DocumentBlock block(String id, DocumentBlockRole role, int order,
                                List<String> section, String text) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(0.1, 0.1, 0.4, 0.05),
                role, order, section, text, null, null, 0.9);
    }

    private String hash(Long paperId) {
        return Long.toHexString(paperId).repeat(64).substring(0, 64);
    }
}
