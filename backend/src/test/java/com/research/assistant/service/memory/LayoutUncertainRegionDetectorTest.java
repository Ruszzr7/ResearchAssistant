package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.DocumentLayoutLane;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutUncertainRegionDetectorTest {

    @Test
    void localizesImpossibleRightToLeftTransitionWithoutFlaggingNormalText() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.now(), 1, List.of(
                block("right", 1, .55, .20, DocumentLayoutLane.RIGHT),
                block("left", 2, .08, .21, DocumentLayoutLane.LEFT),
                block("later", 3, .08, .50, DocumentLayoutLane.LEFT)));

        List<LayoutUncertainRegion> regions = new LayoutUncertainRegionDetector().detect(artifact);

        assertThat(regions).singleElement().satisfies(region -> {
            assertThat(region.issueType()).isEqualTo("READING_ORDER");
            assertThat(region.blockIds()).containsExactly("right", "left");
            assertThat(region.pageAreas()).singleElement();
        });
    }

    @Test
    void groupsNearbyWeakFormulaFragmentsAndExpandsCropToContainingLane() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.now(), 1, List.of(
                laneBody("right-top", 1, .55, .10),
                formula("fragment-a", 2, .70, .30, .08),
                formula("fragment-b", 3, .79, .31, .05),
                formula("separate", 4, .70, .42, .08),
                laneBody("right-bottom", 5, .55, .70)));

        List<LayoutUncertainRegion> regions = new LayoutUncertainRegionDetector().detect(artifact);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).blockIds()).containsExactly("fragment-a", "fragment-b");
        assertThat(regions.get(0).pageAreas()).singleElement().satisfies(area -> {
            assertThat(area.boxes()).singleElement().satisfies(box -> {
                assertThat(box.x()).isLessThanOrEqualTo(.55);
                assertThat(box.right()).isGreaterThanOrEqualTo(.95);
                assertThat(box.y()).isLessThan(.30);
                assertThat(box.bottom()).isGreaterThan(.33);
            });
        });
        assertThat(regions.get(1).blockIds()).containsExactly("separate");
    }

    @Test
    void groupsTheFullNumberedFormulaWhenItsVisualLinesHaveLargerSpacing() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.now(), 1, List.of(
                laneBody("right-top", 1, .55, .22),
                formula("line-1", 2, .65, .30, .22),
                formula("line-2", 3, .65, .35, .28),
                formula("line-3", 4, .65, .41, .24, "e = f. (10)"),
                laneBody("right-bottom", 5, .55, .60)));

        List<LayoutUncertainRegion> regions = new LayoutUncertainRegionDetector().detect(artifact);

        assertThat(regions).singleElement().satisfies(region -> {
            assertThat(region.blockIds()).containsExactly("line-1", "line-2", "line-3");
            assertThat(region.pageAreas()).singleElement().satisfies(area ->
                    assertThat(area.boxes()).singleElement().satisfies(box -> {
                        assertThat(box.y()).isLessThan(.30);
                        assertThat(box.bottom()).isGreaterThan(.43);
                    }));
        });
    }

    @Test
    void doesNotFlagStructuredFormulaAsVisualRecovery() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.now(), 1, List.of(new DocumentBlock("structured", 1,
                new NormalizedBoundingBox(.55, .30, .35, .04), DocumentBlockRole.FORMULA, 1,
                List.of("Method"), "x = y + 1", "x = y + 1", null, .95,
                DocumentBlockContentMode.STRUCTURED, null, DocumentLayoutLane.RIGHT)));

        assertThat(new LayoutUncertainRegionDetector().detect(artifact)).isEmpty();
    }

    @Test
    void keepsReliableAlgorithmBlocksAsContextInsteadOfRecoveryTargets() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser", .9,
                Instant.now(), 1, List.of(
                new DocumentBlock("algorithm-title", 1,
                        new NormalizedBoundingBox(.55, .10, .40, .03), DocumentBlockRole.BODY, 1,
                        List.of("Method"), "Algorithm 1: Test procedure", null, null, .95,
                        DocumentBlockContentMode.TEXT, null, DocumentLayoutLane.RIGHT),
                formula("algorithm-formula", 2, .70, .16, .12),
                new DocumentBlock("algorithm-step", 1,
                        new NormalizedBoundingBox(.55, .21, .40, .03), DocumentBlockRole.BODY, 3,
                        List.of("Method"), "1) Initialize the procedure and return the result.", null, null,
                        .95, DocumentBlockContentMode.TEXT, null, DocumentLayoutLane.RIGHT)));

        List<LayoutUncertainRegion> regions = new LayoutUncertainRegionDetector().detect(artifact);

        assertThat(regions).singleElement().satisfies(region -> {
            assertThat(region.blockIds()).containsExactly("algorithm-formula");
            assertThat(region.pageAreas()).singleElement().satisfies(area ->
                    assertThat(area.boxes()).singleElement().satisfies(box -> {
                        assertThat(box.x()).isLessThanOrEqualTo(.55);
                        assertThat(box.right()).isGreaterThanOrEqualTo(.95);
                    }));
        });
    }

    private DocumentBlock formula(String id, int order, double x, double y, double width) {
        return formula(id, order, x, y, width, "[]");
    }

    private DocumentBlock formula(String id, int order, double x, double y,
                                  double width, String text) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, width, .02),
                DocumentBlockRole.FORMULA, order, List.of("Method"), text, null, null, .88,
                DocumentBlockContentMode.REGION, null, DocumentLayoutLane.RIGHT);
    }

    private DocumentBlock laneBody(String id, int order, double x, double y) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, .40, .03),
                DocumentBlockRole.BODY, order, List.of("Method"),
                "This is a sufficiently long scientific sentence.", null, null, .95,
                DocumentBlockContentMode.TEXT, null, DocumentLayoutLane.RIGHT);
    }

    private DocumentBlock block(String id, int order, double x, double y, DocumentLayoutLane lane) {
        return new DocumentBlock(id, 1, new NormalizedBoundingBox(x, y, .35, .04),
                DocumentBlockRole.BODY, order, List.of("Method"),
                "This is a sufficiently long scientific sentence.", null, null, .95,
                null, null, lane);
    }
}
