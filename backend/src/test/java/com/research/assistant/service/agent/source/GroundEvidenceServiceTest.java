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
