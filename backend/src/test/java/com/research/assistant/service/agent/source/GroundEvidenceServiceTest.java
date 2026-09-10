package com.research.assistant.service.agent.source;

import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundEvidenceServiceTest {

    private final GroundEvidenceService service = new GroundEvidenceService();

    @Test
    void numbersByFirstAnswerAppearanceAndReusesSameSourceNumber() {
        PaperSourceCatalog catalog = catalog();
        String answer = "方法先估计信道。结果提升了十个百分点。随后再次使用信道估计。";
        GroundedAnswer grounded = service.ground(answer, List.of(
                new CitationRequest(18, 28, "source-result", "improves accuracy by ten percent", List.of()),
                new CitationRequest(0, 8, "source-method", "channel estimation", List.of()),
                new CitationRequest(29, answer.length(), "source-method", "channel estimation", List.of())
        ), catalog);

        assertThat(grounded.bindings()).extracting(CitationBinding::citationNumber)
                .containsExactly(1, 2, 1);
        assertThat(grounded.evidenceEntries()).extracting(CitationBinding::citationNumber)
                .containsExactly(1, 2);
        assertThat(grounded.evidenceEntries()).extracting(CitationBinding::quote)
                .containsExactly("We perform channel estimation before decoding.",
                        "The method improves accuracy by ten percent.");
    }

    @Test
    void generatesPreviewServerSideAndRejectsForeignLocator() {
        PaperSourceCatalog catalog = catalog();
        assertThatThrownBy(() -> service.ground("claim", List.of(
                new CitationRequest(0, 5, "source-method", null, List.of("other"))), catalog))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsStandaloneGlyphAsCitationEvidence() {
        SourceObject source = object("source-glyph", "√");
        SourceLocator locator = locator("locator-glyph", source.sourceObjectId(), 6);
        PaperSourceCatalog catalog = new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 6,
                Map.of(source.sourceObjectId(), source),
                Map.of(source.sourceObjectId(), List.of(locator)));

        assertThatThrownBy(() -> service.ground("核心创新点", List.of(
                new CitationRequest(0, 5, source.sourceObjectId(), "√", List.of())), catalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文本质量不足");
    }

    @Test
    void rejectsUnnumberedStandaloneFormulaGlyphAsCitationEvidence() {
        SourceObject source = new SourceObject("formula-glyph", 7, "a".repeat(64), "parser-v1", 3,
                SourceContentType.FORMULA, "√", null, List.of(), "",
                Map.of("textFormat", "PLAIN_TEXT", "textReliable", "false"));
        SourceLocator locator = locator("locator-formula-glyph", source.sourceObjectId(), 6);
        PaperSourceCatalog catalog = new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 6,
                Map.of(source.sourceObjectId(), source), Map.of(source.sourceObjectId(), List.of(locator)));

        assertThatThrownBy(() -> service.ground("核心创新点", List.of(
                new CitationRequest(0, 5, source.sourceObjectId(), "√", List.of())), catalog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文本质量不足");
    }

    @Test
    void collapsesPhysicallyIdenticalSourcesButKeepsSameTextOnAnotherPage() {
        SourceObject first = object("source-first", "The same complete sentence is evidence.");
        SourceObject duplicate = object("source-duplicate", "The same complete sentence is evidence.");
        SourceObject repeated = object("source-repeated", "The same complete sentence is evidence.");
        SourceLocator firstLocator = locator("locator-first", first.sourceObjectId(), 3);
        SourceLocator duplicateLocator = locator("locator-duplicate", duplicate.sourceObjectId(), 3);
        SourceLocator repeatedLocator = locator("locator-repeated", repeated.sourceObjectId(), 4);
        PaperSourceCatalog catalog = new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 4,
                Map.of(first.sourceObjectId(), first, duplicate.sourceObjectId(), duplicate,
                        repeated.sourceObjectId(), repeated),
                Map.of(first.sourceObjectId(), List.of(firstLocator),
                        duplicate.sourceObjectId(), List.of(duplicateLocator),
                        repeated.sourceObjectId(), List.of(repeatedLocator)));

        GroundedAnswer grounded = service.ground("one two three", List.of(
                new CitationRequest(0, 3, first.sourceObjectId(), "", List.of()),
                new CitationRequest(3, 7, duplicate.sourceObjectId(), "", List.of()),
                new CitationRequest(7, 11, repeated.sourceObjectId(), "", List.of())), catalog);

        assertThat(grounded.bindings()).extracting(CitationBinding::citationNumber)
                .containsExactly(1, 1, 2);
        assertThat(grounded.bindings()).extracting(CitationBinding::sourceObjectId)
                .containsExactly(first.sourceObjectId(), first.sourceObjectId(), repeated.sourceObjectId());
        assertThat(grounded.evidenceEntries()).hasSize(2);
    }

    @Test
    void collapsesFormulaEntityAndVisualFallbackByNumberAndOverlap() {
        SourceObject entity = new SourceObject("formula-entity", 7, "a".repeat(64), "parser-v1", 3,
                SourceContentType.FORMULA, "sqrt x (18)", null, List.of(), "18",
                Map.of("textFormat", "PLAIN_TEXT", "textReliable", "false"));
        SourceObject fallback = new SourceObject("formula-fallback", 7, "a".repeat(64), "parser-v1", 3,
                SourceContentType.FORMULA, "公式 (18)的文本提取不可靠", null, List.of(), "18",
                Map.of("textFormat", "VISUAL_FALLBACK", "textReliable", "false",
                        "recoveryMode", "VISUAL_FALLBACK"));
        SourceLocator entityLocator = new SourceLocator("locator-entity", entity.sourceObjectId(), 3,
                "PDF_NORMALIZED", List.of(new NormalizedBoundingBox(.30, .40, .30, .05)), "",
                EvidenceLocator.Precision.FORMULA_REGION);
        SourceLocator fallbackLocator = new SourceLocator("locator-fallback", fallback.sourceObjectId(), 3,
                "PDF_NORMALIZED", List.of(new NormalizedBoundingBox(.28, .38, .36, .09)), "",
                EvidenceLocator.Precision.FORMULA_REGION);
        PaperSourceCatalog catalog = new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 3,
                Map.of(entity.sourceObjectId(), entity, fallback.sourceObjectId(), fallback),
                Map.of(entity.sourceObjectId(), List.of(entityLocator),
                        fallback.sourceObjectId(), List.of(fallbackLocator)));

        String answer = "最核心公式见式（18）。";
        GroundedAnswer grounded = service.ground(answer, List.of(
                new CitationRequest(0, 6, entity.sourceObjectId(), "公式 (18)", List.of()),
                new CitationRequest(6, answer.length(), fallback.sourceObjectId(), "公式 (18)", List.of())), catalog);

        assertThat(grounded.evidenceEntries()).singleElement()
                .extracting(CitationBinding::sourceObjectId).isEqualTo(entity.sourceObjectId());
        assertThat(grounded.bindings()).extracting(CitationBinding::citationNumber)
                .containsExactly(1, 1);
    }

    @Test
    void mergesLocatorSubsetsForTheSameSourceObject() {
        SourceObject source = object("source-split",
                "The complete source text remains one logical evidence unit.");
        SourceLocator first = locator("locator-left", source.sourceObjectId(), 3);
        SourceLocator second = locator("locator-right", source.sourceObjectId(), 3);
        PaperSourceCatalog catalog = new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 3,
                Map.of(source.sourceObjectId(), source),
                Map.of(source.sourceObjectId(), List.of(first, second)));

        GroundedAnswer grounded = service.ground("one two", List.of(
                new CitationRequest(0, 3, source.sourceObjectId(), "", List.of(first.locatorId())),
                new CitationRequest(4, 7, source.sourceObjectId(), "", List.of(second.locatorId()))), catalog);

        assertThat(grounded.bindings()).extracting(CitationBinding::citationNumber)
                .containsExactly(1, 1);
        assertThat(grounded.evidenceEntries()).singleElement()
                .extracting(CitationBinding::locatorIds)
                .isEqualTo(List.of(first.locatorId(), second.locatorId()));
    }

    @Test
    void removesExactlyRepeatedCitationBindingsButKeepsDifferentAnswerRanges() {
        PaperSourceCatalog catalog = catalog();
        String answer = "方法先估计信道。随后再次使用信道估计。";
        CitationRequest first = new CitationRequest(0, 8, "source-method",
                "channel estimation", List.of());

        GroundedAnswer grounded = service.ground(answer, List.of(
                first,
                first,
                new CitationRequest(9, answer.length(), "source-method",
                        "channel estimation", List.of())
        ), catalog);

        assertThat(grounded.bindings()).extracting(
                        CitationBinding::answerStart,
                        CitationBinding::answerEnd,
                        CitationBinding::citationNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 8, 1),
                        org.assertj.core.groups.Tuple.tuple(9, answer.length(), 1));
        assertThat(grounded.evidenceEntries()).hasSize(1);
    }

    private PaperSourceCatalog catalog() {
        SourceObject method = object("source-method", "We perform channel estimation before decoding.");
        SourceObject result = object("source-result", "The method improves accuracy by ten percent.");
        SourceLocator methodLocator = locator("locator-method", "source-method", 1);
        SourceLocator resultLocator = locator("locator-result", "source-result", 2);
        Map<String, SourceObject> objects = new LinkedHashMap<>();
        objects.put(method.sourceObjectId(), method);
        objects.put(result.sourceObjectId(), result);
        return new PaperSourceCatalog(7, "a".repeat(64), "parser-v1", 2, objects,
                Map.of("source-method", List.of(methodLocator), "source-result", List.of(resultLocator)));
    }

    private SourceObject object(String id, String text) {
        return new SourceObject(id, 7, "a".repeat(64), "parser-v1", 3,
                SourceContentType.TEXT, text, null, List.of(), "", Map.of());
    }

    private SourceLocator locator(String id, String sourceId, int page) {
        return new SourceLocator(id, sourceId, page, "PDF_NORMALIZED",
                List.of(new NormalizedBoundingBox(.1, .1, .5, .1)), "",
                EvidenceLocator.Precision.BLOCK);
    }
}
