package com.research.assistant.service.agent.source;

import com.research.assistant.service.agent.action.ActionTarget;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperSourceIndexService;
import com.research.assistant.service.pdf.layout.PaperSemanticSpanBuilder;
import com.research.assistant.service.memory.LayoutUncertainRegion;
import com.research.assistant.service.memory.PaperLayoutRecovery;
import com.research.assistant.service.memory.PaperLayoutRecoveryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSourceCatalogServiceTest {

    private final PaperSourceCatalogService service = new PaperSourceCatalogService(
            mock(PaperLayoutArtifactService.class), new PaperSourceIndexService());

    @Test
    void buildsVersionBoundObjectsAndFindsFormulaAndSection() {
        PaperLayoutArtifact artifact = artifact();
        PaperSourceCatalog first = service.build(artifact);
        PaperSourceCatalog second = service.build(artifact());

        assertThat(first.objects().keySet()).isEqualTo(second.objects().keySet());
        assertThat(first.objects().values()).allMatch(object ->
                object.documentHash().equals("a".repeat(64)) && object.parserVersion().equals("parser-v1"));

        List<RetrievalHit> formula = service.search(first,
                new PaperSearchRequest("Equation (7)", Set.of(SourceContentType.FORMULA), null, null, 5));
        assertThat(formula).isNotEmpty();
        assertThat(first.requireObject(formula.get(0).sourceObjectId()).formulaNumber()).isEqualTo("7");
        assertThat(formula.get(0).retrievalRoutes()).contains("FORMULA_NUMBER");

        List<RetrievalHit> method = service.search(first,
                new PaperSearchRequest("channel estimation", Set.of(), 1, 1, 5));
        assertThat(method).isNotEmpty();
        assertThat(service.readPages(first, 1, 1, 10_000)).isNotEmpty();
        assertThat(service.readSection(first, "Method", 10_000)).isNotEmpty();
        assertThatThrownBy(() -> service.readPages(first, 0, 1, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsChineseFormulaNumberQueryAsAnExactFormulaIdentity() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(12L, "f".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("formula-12", 1, box(.20, .20, .50, .04), DocumentBlockRole.FORMULA,
                        1, List.of("Method"), "H = [H1 H2 … HN]. (12)", null, null, .98),
                // Formula 13 intentionally carries neighbouring context mentioning
                // formula 12.  That mention must not satisfy an explicit formula-12 query.
                new DocumentBlock("formula-13", 1, box(.20, .32, .50, .04), DocumentBlockRole.FORMULA,
                        2, List.of("H = [H1 H2 … HN]. (12)"), "M = H^T. (13)", null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        List<RetrievalHit> hits = service.search(catalog,
                new PaperSearchRequest("公式 12", Set.of(SourceContentType.FORMULA), 1, 1, 5));

        assertThat(hits).hasSize(1);
        assertThat(catalog.requireObject(hits.get(0).sourceObjectId()).formulaNumber()).isEqualTo("12");
    }

    @Test
    void prioritizesTheCanonicalFigureNumberOverCaptionWordOverlap() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(12L, "g".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("caption-4", 1, box(.08, .30, .40, .03), DocumentBlockRole.CAPTION,
                        1, List.of("Results"), "Fig. 4. Ergodic rate versus velocity.", null, null, .98),
                new DocumentBlock("caption-8", 1, box(.52, .30, .40, .03), DocumentBlockRole.CAPTION,
                        2, List.of("Results"), "Fig. 8. Ergodic sum-rate versus vehicle velocity.", null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        List<RetrievalHit> hits = service.search(catalog,
                new PaperSearchRequest("Fig. 8 ergodic sum-rate versus vehicle velocity",
                        Set.of(SourceContentType.FIGURE), 1, 1, 10));

        assertThat(hits).isNotEmpty();
        SourceObject first = catalog.requireObject(hits.get(0).sourceObjectId());
        assertThat(first.contentType()).isEqualTo(SourceContentType.FIGURE);
        assertThat(first.provenance().get("figureNumber")).isEqualTo("8");
        assertThat(hits.get(0).retrievalRoutes()).contains("FIGURE_NUMBER");
    }

    @Test
    void exposesMergedParagraphAsOneSourceWithBlockLevelLocators() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("line-1", 1, box(.1, .2, .8, .025), DocumentBlockRole.BODY,
                        1, List.of("Method"), "The receiver jointly de-", null, null, .98),
                new DocumentBlock("line-2", 1, box(.1, .245, .8, .025), DocumentBlockRole.BODY,
                        2, List.of("Method"), "codes both streams.", null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject paragraph = catalog.objects().values().stream()
                .filter(object -> object.rawContent().contains("jointly decodes"))
                .findFirst().orElseThrow();

        assertThat(paragraph.provenance().get("blockIds")).isEqualTo("line-1,line-2");
        assertThat(catalog.requireLocators(paragraph.sourceObjectId())).hasSize(2);
        assertThat(catalog.requireLocators(paragraph.sourceObjectId()))
                .extracting(SourceLocator::pageNumber).containsOnly(1);
        ActionTarget target = new PaperActionResolver().resolve(catalog, paragraph.sourceObjectId());
        assertThat(target.rects()).hasSize(2);
        assertThat(service.readPages(catalog, 1, 1, 10_000))
                .extracting(SourceObject::rawContent)
                .containsExactly("The receiver jointly decodes both streams.");
    }

    @Test
    void exposesCompoundSourceUnitWithOneMultiRectLocator() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("algorithm", 1, box(.1, .2, .4, .03), DocumentBlockRole.BODY,
                        1, List.of("Method"), "Algorithm 2 Alternating update", null, null, .98),
                new DocumentBlock("step-1", 1, box(.1, .24, .4, .03), DocumentBlockRole.BODY,
                        2, List.of("Method"), "1. Solve the rate subproblem.", null, null, .98),
                new DocumentBlock("step-2", 1, box(.1, .28, .4, .03), DocumentBlockRole.BODY,
                        3, List.of("Method"), "2. Update the beamformer.", null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject algorithm = catalog.objects().values().stream()
                .filter(object -> "ALGORITHM".equals(object.provenance().get("sourceUnitKind")))
                .findFirst().orElseThrow();

        assertThat(algorithm.rawContent()).contains("Algorithm 2", "rate subproblem", "beamformer");
        assertThat(catalog.requireLocators(algorithm.sourceObjectId())).singleElement()
                .satisfies(locator -> assertThat(locator.rects()).hasSize(3));
    }

    @Test
    void figureSourceKeepsCaptionTextAndCaptionAnchoredVisualRegion() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("prose", 1, box(.08, .10, .40, .08), DocumentBlockRole.BODY,
                        1, List.of("Results"),
                        "The next experiment compares the achievable rate of all schemes.",
                        null, null, .98),
                new DocumentBlock("caption", 1, box(.08, .44, .40, .04), DocumentBlockRole.CAPTION,
                        2, List.of("Results"), "Fig. 3. Achievable rate comparison.",
                        null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject figure = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.FIGURE)
                .findFirst().orElseThrow();

        assertThat(figure.rawContent()).isEqualTo("Fig. 3. Achievable rate comparison.");
        assertThat(catalog.objects().values())
                .filteredOn(object -> object.contentType() == SourceContentType.TEXT)
                .noneMatch(object -> object.rawContent().equals(figure.rawContent()));
        assertThat(figure.provenance()).containsEntry("visualRegion", "CAPTION_ANCHORED");
        assertThat(catalog.requireLocators(figure.sourceObjectId())).singleElement()
                .satisfies(locator -> {
                    assertThat(locator.precision()).isEqualTo(
                            com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.VISUAL_REGION);
                    assertThat(locator.rects()).containsExactly(box(.08, .44, .40, .04));
                    assertThat(locator.focusRects()).singleElement()
                            .satisfies(box -> assertThat(box.height()).isGreaterThan(.20));
                });
    }

    @Test
    void linksConfirmedCaptionAndDiscussionWithoutTurningDiscussionIntoFigure() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "r".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("caption", 1, box(.08, .40, .40, .03), DocumentBlockRole.CAPTION,
                        1, List.of("Results"), "Fig. 4. Effective throughput versus user count.",
                        null, null, .98),
                new DocumentBlock("discussion", 1, box(.08, .52, .40, .08), DocumentBlockRole.BODY,
                        2, List.of("Results"),
                        "Fig. 4 shows that RSMA remains stable as the user count increases.",
                        null, null, .98),
                new DocumentBlock("other", 1, box(.52, .52, .40, .08), DocumentBlockRole.BODY,
                        3, List.of("Results"),
                        "Figure 9 is discussed only as future work and has no local caption.",
                        null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject figure = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.FIGURE)
                .findFirst().orElseThrow();
        SourceObject discussion = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.TEXT)
                .filter(object -> object.rawContent().contains("RSMA remains stable"))
                .findFirst().orElseThrow();
        SourceObject unrelated = catalog.objects().values().stream()
                .filter(object -> object.rawContent().contains("future work"))
                .findFirst().orElseThrow();

        assertThat(figure.provenance())
                .containsEntry("figureNumber", "4")
                .containsEntry("captionOf", "figure:4")
                .containsEntry("discussedBy", discussion.sourceObjectId());
        assertThat(discussion.provenance())
                .containsEntry("discussesFigure", figure.sourceObjectId())
                .containsEntry("discussesFigureNumbers", "4");
        assertThat(unrelated.provenance()).doesNotContainKeys(
                "discussesFigure", "discussesFigureNumbers");
        assertThat(discussion.contentType()).isEqualTo(SourceContentType.TEXT);
    }

    @Test
    void figureLocatorCoversEveryCaptionLineButKeepsVisualCropSeparate() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "d".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("prose", 1, box(.52, .10, .41, .08), DocumentBlockRole.BODY,
                        1, List.of("Results"), "The following chart compares the proposed systems.",
                        null, null, .98),
                new DocumentBlock("caption", 1, box(.52, .40, .41, .012), DocumentBlockRole.CAPTION,
                        2, List.of("Results"), "FIGURE 2. Spectral efficiency for N = 3",
                        null, null, .98),
                new DocumentBlock("caption-line-2", 1, box(.52, .416, .40, .012), DocumentBlockRole.BODY,
                        3, List.of("Results"), "and R = OMA throughput with 50% bandwidth.",
                        null, null, .98),
                new DocumentBlock("caption-line-3", 1, box(.52, .432, .38, .012), DocumentBlockRole.FORMULA,
                        4, List.of("Results"), "The cluster-heads are distributed within 150m of the BS.",
                        null, null, .98),
                new DocumentBlock("analysis", 1, box(.52, .48, .41, .08), DocumentBlockRole.BODY,
                        5, List.of("Results"),
                        "Figure 2 shows that the proposed method improves spectral efficiency.",
                        null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject figure = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.FIGURE)
                .findFirst().orElseThrow();
        SourceLocator locator = catalog.requireLocators(figure.sourceObjectId()).get(0);

        assertThat(figure.rawContent()).contains("FIGURE 2", "50% bandwidth", "distributed within 150m")
                .doesNotContain("proposed method improves");
        assertThat(locator.rects()).containsExactly(
                box(.52, .40, .41, .012),
                box(.52, .416, .40, .012),
                box(.52, .432, .38, .012));
        assertThat(locator.focusRects()).singleElement()
                .satisfies(box -> assertThat(box.y()).isLessThan(.40));
        assertThat(catalog.objects().values())
                .filteredOn(object -> object.contentType() == SourceContentType.TEXT)
                .anyMatch(object -> object.rawContent().contains("proposed method improves"))
                .noneMatch(object -> object.rawContent().contains("50% bandwidth"));
    }

    @Test
    void prefersReliableLatexForFormulaEvidence() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(8L, "b".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("formula", 1, box(.2, .35, .6, .06), DocumentBlockRole.FORMULA,
                        1, List.of("Method"), "garbled formula (7)",
                        "\\hat{x}=\\frac{a}{b}", null, .97)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject formula = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.FORMULA)
                .filter(object -> "7".equals(object.formulaNumber()))
                .findFirst().orElseThrow();

        assertThat(formula.rawContent()).isEqualTo("\\hat{x}=\\frac{a}{b}");
        assertThat(formula.provenance()).containsEntry("textFormat", "LATEX")
                .containsEntry("textReliable", "true");
        SourceLocator locator = catalog.requireLocators(formula.sourceObjectId()).get(0);
        assertThat(locator.contentRects()).isEqualTo(locator.rects());
        assertThat(locator.focusRects()).isNotEmpty();
    }

    @Test
    void exposesCrossPageContinuationWithPerPageLocators() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 2, List.of(
                new DocumentBlock("algorithm", 1, box(.1, .86, .4, .03), DocumentBlockRole.BODY,
                        1, List.of("Method"), "Algorithm 4 Joint optimization", null, null, .98),
                new DocumentBlock("step-1", 1, box(.1, .90, .4, .03), DocumentBlockRole.BODY,
                        2, List.of("Method"), "1. Initialize all variables.", null, null, .98),
                new DocumentBlock("step-2", 2, box(.1, .08, .4, .03), DocumentBlockRole.BODY,
                        3, List.of("Method"), "2. Solve the convex subproblem.", null, null, .98),
                new DocumentBlock("step-3", 2, box(.1, .12, .4, .03), DocumentBlockRole.BODY,
                        4, List.of("Method"), "3. Update the solution.", null, null, .98)));

        PaperSourceCatalog catalog = service.build(artifact);
        SourceObject continuation = catalog.objects().values().stream()
                .filter(object -> object.provenance().containsKey("continuationId"))
                .findFirst().orElseThrow();

        assertThat(catalog.requireLocators(continuation.sourceObjectId()))
                .extracting(SourceLocator::pageNumber).containsExactly(1, 2);
        assertThat(service.search(catalog,
                new PaperSearchRequest("convex subproblem", Set.of(), 2, 2, 10)))
                .extracting(RetrievalHit::sourceObjectId)
                .contains(continuation.sourceObjectId());
        assertThat(service.readPages(catalog, 2, 2, 10_000))
                .extracting(SourceObject::sourceObjectId)
                .contains(continuation.sourceObjectId());
    }

    @Test
    void searchesRecoveredContentAndKeepsOriginalRegionLocator() {
        PaperLayoutArtifactService artifacts = mock(PaperLayoutArtifactService.class);
        PaperLayoutRecoveryStore recoveries = mock(PaperLayoutRecoveryStore.class);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .8,
                Instant.now(), 1, List.of(new DocumentBlock("broken", 1,
                box(.2, .3, .5, .08), DocumentBlockRole.FORMULA, 1, List.of("Method"),
                "x ? y", null, null, .5)));
        when(recoveries.read(artifact)).thenReturn(List.of(new PaperLayoutRecovery(
                "lr-p1-001", "CORRECTED", "VISUAL_CONTENT", List.of("broken"),
                List.of(new LayoutUncertainRegion.PageArea(1, List.of(box(.2, .3, .5, .08)))),
                "z equals alpha plus beta", "FORMULA", "VISUAL_RECOVERY")));
        PaperSourceCatalogService recoveredService = new PaperSourceCatalogService(
                artifacts, new PaperSourceIndexService(), new PaperSemanticSpanBuilder(), recoveries);

        PaperSourceCatalog catalog = recoveredService.build(artifact);
        RetrievalHit hit = recoveredService.search(catalog,
                new PaperSearchRequest("alpha plus beta", Set.of(SourceContentType.FORMULA), 1, 1, 5))
                .get(0);

        assertThat(catalog.requireObject(hit.sourceObjectId()).provenance().get("source"))
                .isEqualTo("VISUAL_RECOVERY");
        assertThat(catalog.requireLocators(hit.sourceObjectId())).singleElement().satisfies(locator -> {
            assertThat(locator.pageNumber()).isEqualTo(1);
            assertThat(locator.rects()).containsExactly(box(.2, .3, .5, .08));
        });
    }

    @Test
    void keepsUnresolvedFormulaAsSearchableVisualEvidenceWithNeighbouringContext() {
        PaperLayoutArtifactService artifacts = mock(PaperLayoutArtifactService.class);
        PaperLayoutRecoveryStore recoveries = mock(PaperLayoutRecoveryStore.class);
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .8,
                Instant.now(), 1, List.of(
                new DocumentBlock("before", 1, box(.1, .20, .8, .04), DocumentBlockRole.BODY,
                        1, List.of("Method"), "We define the optimization objective.", null, null, .98),
                new DocumentBlock("broken", 1, box(.2, .30, .5, .08), DocumentBlockRole.FORMULA,
                        2, List.of("Method"), "x ? y (7)", null, null, .5),
                new DocumentBlock("after", 1, box(.1, .42, .8, .04), DocumentBlockRole.BODY,
                        3, List.of("Method"), "The constraint guarantees feasibility.", null, null, .98)));
        when(recoveries.read(artifact)).thenReturn(List.of(new PaperLayoutRecovery(
                "lr-p1-001", "UNRESOLVED", "VISUAL_CONTENT", List.of("broken"),
                List.of(new LayoutUncertainRegion.PageArea(1, List.of(box(.2, .3, .5, .08)))),
                "", "TEXT", "VISUAL_RECOVERY")));
        PaperSourceCatalogService recoveredService = new PaperSourceCatalogService(
                artifacts, new PaperSourceIndexService(), new PaperSemanticSpanBuilder(), recoveries);

        PaperSourceCatalog catalog = recoveredService.build(artifact);
        SourceObject source = catalog.objects().values().stream()
                .filter(object -> object.contentType() == SourceContentType.FORMULA)
                .filter(object -> "7".equals(object.formulaNumber()))
                .findFirst().orElseThrow();
        RetrievalHit hit = recoveredService.search(catalog,
                        new PaperSearchRequest("Equation (7)", Set.of(SourceContentType.FORMULA), 1, 1, 5))
                .stream().filter(candidate -> candidate.sourceObjectId().equals(source.sourceObjectId()))
                .findFirst().orElseThrow();

        assertThat(source.provenance().get("textReliable")).isEqualTo("false");
        assertThat(catalog.objects().values()).noneMatch(object ->
                "lr-p1-001".equals(object.provenance().get("recoveryRegionId")));
        assertThat(hit.retrievalRoutes()).contains("FORMULA_NUMBER");
        assertThat(catalog.requireLocators(source.sourceObjectId())).singleElement()
                .satisfies(locator -> assertThat(locator.rects()).hasSize(1));
        assertThat(catalog.objects().values()).anySatisfy(object ->
                assertThat(object.rawContent()).contains("x ? y (7)"));
    }

    @Test
    void excludesStandaloneMathGlyphAndTinyOcrTokenFromTextSources() {
        PaperLayoutArtifact artifact = new PaperLayoutArtifact(7L, "c".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 1, List.of(
                new DocumentBlock("radical", 1, box(.24, .08, .02, .03), DocumentBlockRole.BODY,
                        1, List.of("Theorem 1"), "√", null, null, .9,
                        com.research.assistant.service.pdf.layout.DocumentBlockContentMode.TEXT,
                        new com.research.assistant.service.pdf.layout.MathContentProfile(
                                com.research.assistant.service.pdf.layout.MathContentLevel.LIGHT,
                                .8, 1, List.of(), "test")),
                new DocumentBlock("token", 1, box(.24, .12, .04, .02), DocumentBlockRole.BODY,
                        2, List.of("Theorem 1"), "tot", null, null, .9),
                new DocumentBlock("prose", 1, box(.1, .20, .8, .04), DocumentBlockRole.BODY,
                        3, List.of("Theorem 1"), "The proposed method improves the achievable rate.",
                        null, null, .9)));

        PaperSourceCatalog catalog = service.build(artifact);

        assertThat(catalog.objects().values()).extracting(SourceObject::rawContent)
                .containsExactly("The proposed method improves the achievable rate.")
                .doesNotContain("√", "tot");
    }

    private PaperLayoutArtifact artifact() {
        return new PaperLayoutArtifact(7L, "a".repeat(64), "parser-v1", .95,
                Instant.parse("2026-01-01T00:00:00Z"), 2, List.of(
                new DocumentBlock("heading", 1, box(.1, .1, .8, .05), DocumentBlockRole.HEADING,
                        1, List.of("Method"), "Method", null, null, .99),
                new DocumentBlock("body", 1, box(.1, .2, .8, .08), DocumentBlockRole.BODY,
                        2, List.of("Method"), "We perform channel estimation before decoding.", null, null, .98),
                new DocumentBlock("formula", 1, box(.2, .35, .6, .06), DocumentBlockRole.FORMULA,
                        3, List.of("Method"), "SINR = P_s / (I + N) (7)", null, null, .97),
                new DocumentBlock("result", 2, box(.1, .2, .8, .08), DocumentBlockRole.BODY,
                        4, List.of("Results"), "The proposed method improves accuracy by ten percent.", null, null, .96)
        ));
    }

    private NormalizedBoundingBox box(double x, double y, double width, double height) {
        return new NormalizedBoundingBox(x, y, width, height);
    }
}
