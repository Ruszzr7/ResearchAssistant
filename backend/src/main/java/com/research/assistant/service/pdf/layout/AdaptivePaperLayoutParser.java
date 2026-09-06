package com.research.assistant.service.pdf.layout;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Local layout parser. PDFBox produces the artifact and the quality assessor
 * records any uncertainty for later model-assisted recovery.
 */
@Primary
@Component
public class AdaptivePaperLayoutParser implements PaperLayoutParser {

    static final String VERSION = "adaptive-layout-v1";

    private final PdfBoxPaperLayoutParser primary;
    private final PaperLayoutQualityAssessor assessor;

    public AdaptivePaperLayoutParser(PdfBoxPaperLayoutParser primary,
                                     PaperLayoutQualityAssessor assessor) {
        this.primary = primary;
        this.assessor = assessor;
    }

    @Override
    public PaperLayoutArtifact parse(Long paperId, File file) {
        return parse(paperId, file, PdfDocumentFingerprint.sha256(file));
    }

    @Override
    public PaperLayoutArtifact parse(Long paperId, File file, String documentHash) {
        PaperLayoutArtifact primaryArtifact = primary.parse(paperId, file, documentHash);
        LayoutQualityReport primaryQuality = assessor.assess(primaryArtifact);
        return select(primaryArtifact, primaryQuality);
    }

    @Override
    public String parserVersion() {
        return VERSION + "-" + primary.parserVersion();
    }

    private PaperLayoutArtifact select(PaperLayoutArtifact selected, LayoutQualityReport primaryQuality) {
        List<String> issues = primaryQuality.issues().stream().map(Enum::name).toList();
        LayoutArtifactProvenance provenance = new LayoutArtifactProvenance(
                primary.parserVersion(), primary.parserVersion(),
                primaryQuality.fallbackRecommended(), false, false,
                primaryQuality.score(), null, issues, "");
        return new PaperLayoutArtifact(
                selected.paperId(), selected.documentHash(), parserVersion(), primaryQuality.score(),
                Instant.now(), selected.pageCount(), selected.blocks(), provenance);
    }
}
