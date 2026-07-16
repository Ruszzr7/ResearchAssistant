package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.pdf.formula.region.ConfirmedFormulaRegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Maps untrusted viewport geometry back to versioned semantic layout blocks. */
@Service
public class SelectionAnchorResolver {

    private static final int MAX_MATCHED_BLOCKS = 12;

    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final ConfirmedFormulaRegionService confirmedFormulaRegions;

    public SelectionAnchorResolver(PaperLayoutEvidencePolicy evidencePolicy) {
        this(evidencePolicy, null);
    }

    @Autowired
    public SelectionAnchorResolver(PaperLayoutEvidencePolicy evidencePolicy,
                                   ConfirmedFormulaRegionService confirmedFormulaRegions) {
        this.evidencePolicy = evidencePolicy;
        this.confirmedFormulaRegions = confirmedFormulaRegions;
    }

    public SelectionAnchor resolve(PaperLayoutArtifact artifact,
                                   int page,
                                   List<NormalizedBoundingBox> boxes,
                                   String anchorText,
                                   SelectionAnchorKind preferredKind) {
        validate(artifact, page, boxes);
        String safeText = anchorText == null ? "" : anchorText.strip();
        if (preferredKind == SelectionAnchorKind.FORMULA && confirmedFormulaRegions != null) {
            java.util.Optional<SelectionAnchor> confirmed = confirmedFormulaRegions.resolve(
                    artifact, page, boxes);
            if (confirmed.isPresent()) return confirmed.get();
        }
        List<Match> matches = artifact.blocks().stream()
                .filter(block -> block.page() == page)
                .map(block -> match(block, boxes, safeText))
                .filter(match -> match.geometryScore() >= 0.08 || match.score() >= 0.30)
                .sorted(Comparator.comparingDouble(Match::score).reversed()
                        .thenComparingInt(match -> match.block().readingOrder()))
                .toList();

        double bestScore = matches.isEmpty() ? 0 : matches.get(0).score();
        List<Match> selected = matches.stream()
                .filter(match -> match.score() >= Math.max(0.28, bestScore * 0.52))
                .limit(MAX_MATCHED_BLOCKS)
                .sorted(Comparator.comparingInt(match -> match.block().readingOrder()))
                .toList();
        List<DocumentBlock> blocks = selected.stream().map(Match::block).toList();

        double geometryAgreement = selectionGeometryAgreement(boxes, blocks);
        double textAgreement = LayoutTextSimilarity.queryCoverage(
                safeText, blocks.stream().map(DocumentBlock::text).reduce("", (a, b) -> a + " " + b));
        double blockConfidence = blocks.stream().mapToDouble(DocumentBlock::confidence).average().orElse(0);
        double confidence = clamp(0.58 * geometryAgreement + 0.27 * textAgreement + 0.15 * blockConfidence);
        SelectionAnchorKind kind = determineKind(
                preferredKind, blocks, safeText, geometryAgreement, textAgreement, confidence);

        return new SelectionAnchor(
                artifact.paperId(),
                page,
                boxes,
                safeText,
                blocks.stream().map(DocumentBlock::id).toList(),
                exactRange(blocks, safeText),
                kind,
                confidence,
                artifact.documentHash(),
                artifact.parserVersion()
        );
    }

    private Match match(DocumentBlock block,
                        List<NormalizedBoundingBox> boxes,
                        String anchorText) {
        double geometryScore = boxes.stream()
                .mapToDouble(box -> NormalizedBoxMath.selectionCoverage(box, block.bbox()))
                .average()
                .orElse(0);
        double textScore = LayoutTextSimilarity.queryCoverage(anchorText, block.text());
        return new Match(block, geometryScore, clamp(0.78 * geometryScore + 0.22 * textScore));
    }

    private double selectionGeometryAgreement(List<NormalizedBoundingBox> boxes,
                                              List<DocumentBlock> blocks) {
        if (blocks.isEmpty()) {
            return 0;
        }
        return boxes.stream()
                .mapToDouble(box -> blocks.stream()
                        .mapToDouble(block -> NormalizedBoxMath.selectionCoverage(box, block.bbox()))
                        .max()
                        .orElse(0))
                .average()
                .orElse(0);
    }

    private SelectionAnchorKind determineKind(SelectionAnchorKind preferredKind,
                                              List<DocumentBlock> blocks,
                                              String anchorText,
                                              double geometryAgreement,
                                              double textAgreement,
                                              double confidence) {
        if (preferredKind == SelectionAnchorKind.REGION || blocks.isEmpty()) {
            return SelectionAnchorKind.REGION;
        }
        boolean hasAllowed = blocks.stream().anyMatch(evidencePolicy::isAllowed);
        boolean hasFormula = blocks.stream().anyMatch(block -> block.role() == DocumentBlockRole.FORMULA);
        boolean hasTable = blocks.stream().anyMatch(block -> block.role() == DocumentBlockRole.TABLE);
        boolean hasRegionOnly = blocks.stream().anyMatch(block ->
                block.contentMode() == DocumentBlockContentMode.REGION);
        if (hasRegionOnly && (hasFormula || hasTable)) {
            return SelectionAnchorKind.REGION;
        }
        if (preferredKind == SelectionAnchorKind.FORMULA) {
            return hasFormula && geometryAgreement >= 0.30
                    ? SelectionAnchorKind.FORMULA : SelectionAnchorKind.REGION;
        }
        if (preferredKind == SelectionAnchorKind.TABLE) {
            return hasTable && geometryAgreement >= 0.30
                    ? SelectionAnchorKind.TABLE : SelectionAnchorKind.REGION;
        }
        if (!hasAllowed || geometryAgreement < 0.40) {
            return SelectionAnchorKind.REGION;
        }
        if (hasFormula && blocks.stream().allMatch(block -> block.role() == DocumentBlockRole.FORMULA)) {
            return SelectionAnchorKind.FORMULA;
        }
        if (hasTable && blocks.stream().allMatch(block -> block.role() == DocumentBlockRole.TABLE)) {
            return SelectionAnchorKind.TABLE;
        }
        if (!anchorText.isBlank() && textAgreement >= 0.25 && confidence >= 0.52) {
            return SelectionAnchorKind.TEXT;
        }
        return SelectionAnchorKind.REGION;
    }

    private SelectionTokenRange exactRange(List<DocumentBlock> blocks, String anchorText) {
        if (blocks.size() != 1 || anchorText.isBlank()) {
            return null;
        }
        String blockText = blocks.get(0).text();
        int start = blockText.toLowerCase(Locale.ROOT)
                .indexOf(anchorText.toLowerCase(Locale.ROOT));
        return start < 0 ? null : new SelectionTokenRange(start, start + anchorText.length());
    }

    private void validate(PaperLayoutArtifact artifact,
                          int page,
                          List<NormalizedBoundingBox> boxes) {
        if (artifact == null || page < 1 || page > artifact.pageCount()) {
            throw new IllegalArgumentException("invalid selection page");
        }
        if (boxes == null || boxes.isEmpty() || boxes.size() > 100) {
            throw new IllegalArgumentException("invalid selection boxes");
        }
        List<NormalizedBoundingBox> invalid = new ArrayList<>();
        for (NormalizedBoundingBox box : boxes) {
            if (box == null || NormalizedBoxMath.area(box) <= 0) {
                invalid.add(box);
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("empty selection box");
        }
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record Match(DocumentBlock block, double geometryScore, double score) {
    }
}
