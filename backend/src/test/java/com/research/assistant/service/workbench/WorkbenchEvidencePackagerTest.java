package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchEvidencePackagerTest {

    private final WorkbenchEvidencePackager packager = new WorkbenchEvidencePackager();

    @Test
    void keepsSelectionAndRemovesOverlappingDuplicateParagraphs() {
        LayoutEvidence selected = evidence("selected", "Selected text", 1.0, true,
                new NormalizedBoundingBox(0.1, 0.1, 0.4, 0.05));
        LayoutEvidence first = evidence("first", "The SINR is defined by the following expression.", 0.9, false,
                new NormalizedBoundingBox(0.1, 0.2, 0.4, 0.06));
        LayoutEvidence duplicate = evidence("duplicate",
                "The SINR is defined by the following expression.", 0.8, false,
                new NormalizedBoundingBox(0.1, 0.2, 0.4, 0.06));

        List<LayoutEvidence> result = packager.pack(List.of(duplicate, selected, first), 10, 5_000);

        assertThat(result).extracting(LayoutEvidence::blockId).containsExactly("selected", "first");
    }

    @Test
    void dropsLowRelativeScoreButNotTheOnlyCandidate() {
        List<LayoutEvidence> result = packager.pack(List.of(
                evidence("strong", "Strong matching paragraph", 0.9, false,
                        new NormalizedBoundingBox(0.1, 0.1, 0.4, 0.05)),
                evidence("noise", "Weak generic paragraph", 0.05, false,
                        new NormalizedBoundingBox(0.1, 0.3, 0.4, 0.05))), 10, 5_000);

        assertThat(result).extracting(LayoutEvidence::blockId).containsExactly("strong");
    }

    @Test
    void mergesAWrappedTheoremSentenceButKeepsItsPhysicalLineBoxes() {
        LayoutEvidence first = new LayoutEvidence("lay_first", 7L, "p6-b1", 6,
                new NormalizedBoundingBox(.08, .2, .41, .03), DocumentBlockRole.BODY, 10,
                List.of("Theorem 1"), "Theorem 1. The lower bound for the ergodic rate of",
                .9, false, .9, "a".repeat(64), "parser-v1");
        LayoutEvidence second = new LayoutEvidence("lay_second", 7L, "p6-b2", 6,
                new NormalizedBoundingBox(.08, .233, .41, .03), DocumentBlockRole.BODY, 11,
                List.of("Theorem 1"), "the common stream is given by",
                .88, false, .9, "a".repeat(64), "parser-v1");

        List<LayoutEvidence> result = packager.pack(List.of(second, first), 10, 5_000);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.text()).contains("ergodic rate of the common stream");
            assertThat(item.locator().targetBoxes()).hasSize(2);
            assertThat(item.locator().precision())
                    .isEqualTo(com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.TEXT_SPAN);
        });
    }

    private LayoutEvidence evidence(String id, String text, double score, boolean selected,
                                    NormalizedBoundingBox bbox) {
        return new LayoutEvidence("lay_" + id, 7L, id, 1, bbox, DocumentBlockRole.BODY, 1,
                List.of("System Model"), text, score, selected, 0.9,
                "a".repeat(64), "parser-v1");
    }
}
