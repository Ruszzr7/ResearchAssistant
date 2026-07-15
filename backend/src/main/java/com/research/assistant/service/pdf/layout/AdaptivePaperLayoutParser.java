package com.research.assistant.service.pdf.layout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Fast-first layout parser. PDFBox is always evaluated first; the configured
 * external adapter is only executed for low-quality artifacts and is accepted
 * only when it produces a measurable quality gain.
 */
@Primary
@Component
public class AdaptivePaperLayoutParser implements PaperLayoutParser {

    static final String VERSION = "adaptive-layout-v1";
    private static final double MINIMUM_EXTERNAL_QUALITY = 0.60;
    private static final double MINIMUM_QUALITY_GAIN = 0.04;
    private static final Logger log = LoggerFactory.getLogger(AdaptivePaperLayoutParser.class);

    private final PdfBoxPaperLayoutParser primary;
    private final ExternalLayoutParserAdapter fallback;
    private final PaperLayoutQualityAssessor assessor;

    public AdaptivePaperLayoutParser(PdfBoxPaperLayoutParser primary,
                                     ExternalLayoutParserAdapter fallback,
                                     PaperLayoutQualityAssessor assessor) {
        this.primary = primary;
        this.fallback = fallback;
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
        if (!primaryQuality.fallbackRecommended()) {
            return select(primaryArtifact, primaryQuality, null, false, false, "");
        }
        if (!fallback.enabled()) {
            return select(primaryArtifact, primaryQuality, null, false, false,
                    "FALLBACK_NOT_CONFIGURED");
        }

        ExternalLayoutParseResult attempt = fallback.parse(paperId, file, documentHash);
        if (!attempt.success() || attempt.artifact() == null) {
            log.info("layout_fallback_rejected paperId={} primaryQuality={} code={}",
                    paperId, primaryQuality.score(), attempt.errorCode());
            return select(primaryArtifact, primaryQuality, null, true, false, attempt.errorCode());
        }
        LayoutQualityReport externalQuality = assessor.assess(attempt.artifact());
        boolean accepted = externalQuality.score() >= MINIMUM_EXTERNAL_QUALITY
                && externalQuality.score() >= primaryQuality.score() + MINIMUM_QUALITY_GAIN;
        if (!accepted) {
            log.info("layout_fallback_no_gain paperId={} primaryQuality={} fallbackQuality={}",
                    paperId, primaryQuality.score(), externalQuality.score());
            return select(primaryArtifact, primaryQuality, externalQuality, true, false,
                    "FALLBACK_NO_QUALITY_GAIN");
        }
        log.info("layout_fallback_accepted paperId={} parser={} primaryQuality={} fallbackQuality={}",
                paperId, attempt.artifact().parserVersion(), primaryQuality.score(), externalQuality.score());
        return select(attempt.artifact(), primaryQuality, externalQuality, true, true, "");
    }

    @Override
    public String parserVersion() {
        return VERSION + "-" + primary.parserVersion() + "-" + fallback.policyFingerprint();
    }

    private PaperLayoutArtifact select(PaperLayoutArtifact selected,
                                       LayoutQualityReport primaryQuality,
                                       LayoutQualityReport fallbackQuality,
                                       boolean attempted,
                                       boolean accepted,
                                       String failureCode) {
        String selectedParser = accepted ? selected.parserVersion() : primary.parserVersion();
        double selectedQuality = accepted && fallbackQuality != null
                ? fallbackQuality.score() : primaryQuality.score();
        List<String> issues = primaryQuality.issues().stream().map(Enum::name).toList();
        LayoutArtifactProvenance provenance = new LayoutArtifactProvenance(
                primary.parserVersion(), selectedParser,
                primaryQuality.fallbackRecommended(), attempted, accepted,
                primaryQuality.score(), fallbackQuality == null ? null : fallbackQuality.score(),
                issues, failureCode);
        return new PaperLayoutArtifact(
                selected.paperId(), selected.documentHash(), parserVersion(), selectedQuality,
                Instant.now(), selected.pageCount(), selected.blocks(), provenance);
    }
}
