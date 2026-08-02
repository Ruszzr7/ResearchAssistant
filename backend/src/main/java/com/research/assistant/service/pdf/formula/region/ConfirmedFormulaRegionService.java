package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.entity.PaperFormulaRegionRecord;
import com.research.assistant.mapper.PaperFormulaRegionMapper;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.EvidenceOrigin;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Looks up user-confirmed formula regions and projects them as grounded evidence. */
@Service
public class ConfirmedFormulaRegionService {

    public static final String BLOCK_PREFIX = "formula-region-";
    private static final double MIN_OVERLAP = 0.55;

    private final PaperFormulaRegionMapper mapper;

    public ConfirmedFormulaRegionService(PaperFormulaRegionMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<PaperFormulaRegionRecord> find(PaperLayoutArtifact artifact,
                                                   int page,
                                                   List<NormalizedBoundingBox> boxes) {
        if (artifact == null || boxes == null || boxes.isEmpty()) return Optional.empty();
        return mapper.selectConfirmedOnPage(
                        artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), page).stream()
                .filter(record -> record.getLatex() != null && !record.getLatex().isBlank())
                .map(record -> new Match(record, FormulaRegionGeometry.bestOverlap(boxes, box(record))))
                .filter(match -> match.overlap() >= MIN_OVERLAP)
                .max(Comparator.comparingDouble(Match::overlap)
                        .thenComparing(match -> match.record().getUpdatedAt(),
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(Match::record);
    }

    public Optional<SelectionAnchor> resolve(PaperLayoutArtifact artifact,
                                             int page,
                                             List<NormalizedBoundingBox> boxes) {
        return find(artifact, page, boxes).map(record -> anchor(artifact, record));
    }

    public Optional<LayoutEvidence> evidence(PaperLayoutArtifact artifact, SelectionAnchor anchor) {
        if (anchor == null || anchor.kind() != SelectionAnchorKind.FORMULA) return Optional.empty();
        return find(artifact, anchor.page(), anchor.boxes())
                .map(record -> new LayoutEvidence(
                        evidenceId(artifact, record),
                        artifact.paperId(),
                        BLOCK_PREFIX + record.getId(),
                        record.getPageNumber(),
                        box(record),
                        DocumentBlockRole.FORMULA,
                        0,
                        List.of(),
                        "[已确认公式：" + record.getLatex() + "]",
                        1,
                        true,
                        safeConfidence(record),
                        artifact.documentHash(),
                        artifact.parserVersion(),
                        DocumentBlockContentMode.STRUCTURED,
                        record.getLatex(),
                        List.of(),
                        List.of(),
                        EvidenceOrigin.CONFIRMED_FORMULA,
                        new EvidenceLocator(box(record), record.getLatex(),
                                EvidenceLocator.Precision.FORMULA_REGION),
                        List.of("CONFIRMED_FORMULA")));
    }

    public SelectionAnchor anchor(PaperLayoutArtifact artifact, PaperFormulaRegionRecord record) {
        NormalizedBoundingBox bbox = box(record);
        return new SelectionAnchor(
                artifact.paperId(),
                record.getPageNumber(),
                List.of(bbox),
                record.getLatex(),
                List.of(BLOCK_PREFIX + record.getId()),
                null,
                SelectionAnchorKind.FORMULA,
                safeConfidence(record),
                artifact.documentHash(),
                artifact.parserVersion());
    }

    public NormalizedBoundingBox box(PaperFormulaRegionRecord record) {
        return new NormalizedBoundingBox(
                value(record.getBoxX()), value(record.getBoxY()),
                value(record.getBoxWidth()), value(record.getBoxHeight()));
    }

    private double safeConfidence(PaperFormulaRegionRecord record) {
        return Math.max(0, Math.min(1, value(record.getConfidence())));
    }

    private double value(Double value) {
        return value == null ? 0 : value;
    }

    private String evidenceId(PaperLayoutArtifact artifact, PaperFormulaRegionRecord record) {
        String value = artifact.paperId() + "|" + artifact.documentHash() + "|"
                + artifact.parserVersion() + "|formula-region|" + record.getRegionKey();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "frm_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Match(PaperFormulaRegionRecord record, double overlap) { }
}
