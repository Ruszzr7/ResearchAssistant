package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.pdf.formula.region.ConfirmedFormulaRegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        return resolve(artifact, page, boxes, anchorText, preferredKind, null);
    }

    public SelectionAnchor resolve(PaperLayoutArtifact artifact,
                                   int page,
                                   List<NormalizedBoundingBox> boxes,
                                   String anchorText,
                                   SelectionAnchorKind preferredKind,
                                   ClientTextAnchor clientTextAnchor) {
        validate(artifact, page, boxes);
        if (clientTextAnchor != null && clientTextAnchor.page() != page) {
            throw new IllegalArgumentException("client text anchor page mismatch");
        }
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
        List<SelectionBlockRange> blockRanges = LayoutTextNormalizer.locate(blocks, safeText);
        SelectionMappingStatus mappingStatus = determineMappingStatus(
                blocks, safeText, geometryAgreement, textAgreement, blockRanges);
        SelectionContentType contentType = determineContentType(blocks);
        SelectionEvidenceUse evidenceUse = determineEvidenceUse(blocks, mappingStatus, contentType);

        return new SelectionAnchor(
                artifact.paperId(),
                page,
                boxes,
                safeText,
                blocks.stream().map(DocumentBlock::id).toList(),
                legacyTokenRange(blockRanges),
                kind,
                confidence,
                artifact.documentHash(),
                artifact.parserVersion(),
                mappingStatus,
                contentType,
                evidenceUse,
                blockRanges,
                clientTextAnchor
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

    private SelectionMappingStatus determineMappingStatus(List<DocumentBlock> blocks,
                                                          String anchorText,
                                                          double geometryAgreement,
                                                          double textAgreement,
                                                          List<SelectionBlockRange> blockRanges) {
        if (blocks.isEmpty() || geometryAgreement < 0.30) return SelectionMappingStatus.REGION;
        if (!anchorText.isBlank() && !blockRanges.isEmpty()) return SelectionMappingStatus.EXACT;
        if (!anchorText.isBlank() && textAgreement >= 0.55) return SelectionMappingStatus.PARTIAL;
        return geometryAgreement >= 0.55 ? SelectionMappingStatus.PARTIAL : SelectionMappingStatus.REGION;
    }

    private SelectionContentType determineContentType(List<DocumentBlock> blocks) {
        if (blocks.isEmpty()) return SelectionContentType.UNKNOWN;
        if (blocks.stream().allMatch(block -> block.role() == DocumentBlockRole.REFERENCE)) {
            return SelectionContentType.REFERENCE;
        }
        if (blocks.stream().allMatch(block -> block.role() == DocumentBlockRole.FORMULA)) {
            return SelectionContentType.FORMULA;
        }
        if (blocks.stream().allMatch(block -> block.role() == DocumentBlockRole.TABLE)) {
            return SelectionContentType.TABLE;
        }
        if (blocks.stream().anyMatch(block ->
                block.mathProfile().level() == MathContentLevel.MATH_RICH)) {
            return SelectionContentType.MATH_RICH_TEXT;
        }
        return SelectionContentType.PLAIN_TEXT;
    }

    private SelectionEvidenceUse determineEvidenceUse(List<DocumentBlock> blocks,
                                                       SelectionMappingStatus mappingStatus,
                                                       SelectionContentType contentType) {
        if (contentType == SelectionContentType.REFERENCE) return SelectionEvidenceUse.METADATA_ONLY;
        if (mappingStatus == SelectionMappingStatus.REGION
                || blocks.stream().noneMatch(evidencePolicy::isAllowed)) {
            return SelectionEvidenceUse.VISUAL_ONLY;
        }
        return SelectionEvidenceUse.CLAIM_EVIDENCE;
    }

    private SelectionTokenRange legacyTokenRange(List<SelectionBlockRange> ranges) {
        if (ranges.size() != 1) return null;
        SelectionBlockRange range = ranges.get(0);
        return new SelectionTokenRange(range.start(), range.end());
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
