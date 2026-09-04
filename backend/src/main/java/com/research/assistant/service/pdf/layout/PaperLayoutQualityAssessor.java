package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic quality gate for deciding whether an expensive parser is useful. */
@Component
public class PaperLayoutQualityAssessor {

    static final double FALLBACK_THRESHOLD = 0.68;
    private static final double READING_ORDER_ISSUE_THRESHOLD = 0.92;

    public LayoutQualityReport assess(PaperLayoutArtifact artifact) {
        if (artifact == null) {
            return new LayoutQualityReport(0, true, 0, 0, 0, 0, 0,
                    List.of(LayoutQualityIssue.NO_TEXT_BLOCKS));
        }
        List<DocumentBlock> blocks = artifact.blocks();
        int pageCount = Math.max(1, artifact.pageCount());
        int characters = blocks.stream().mapToInt(block -> block.text().length()).sum();
        double charactersPerPage = characters / (double) pageCount;
        double densityScore = clamp(charactersPerPage / 650.0);
        double geometryRatio = blocks.isEmpty() ? 0 : blocks.stream()
                .filter(this::hasValidGeometry).count() / (double) blocks.size();
        double blockConfidence = blocks.stream().mapToDouble(DocumentBlock::confidence)
                .average().orElse(0);
        double cleanTextRatio = cleanTextRatio(blocks);
        ReadingOrderAssessment orderAssessment = readingOrderAssessment(blocks);
        double orderScore = orderAssessment.score();

        List<LayoutQualityIssue> issues = new ArrayList<>();
        if (blocks.isEmpty() || characters == 0) issues.add(LayoutQualityIssue.NO_TEXT_BLOCKS);
        if (charactersPerPage < 100) issues.add(LayoutQualityIssue.SPARSE_TEXT_COVERAGE);
        if (cleanTextRatio < 0.94) issues.add(LayoutQualityIssue.CORRUPTED_TEXT);
        if (geometryRatio < 0.96) issues.add(LayoutQualityIssue.INVALID_GEOMETRY);
        if (blockConfidence < 0.62) issues.add(LayoutQualityIssue.LOW_BLOCK_CONFIDENCE);
        if (orderAssessment.unstable()) issues.add(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        if (artifact.layoutConfidence() < 0.65) issues.add(LayoutQualityIssue.LOW_LAYOUT_CONFIDENCE);

        double score = 0.28 * artifact.layoutConfidence()
                + 0.24 * densityScore
                + 0.16 * geometryRatio
                + 0.14 * cleanTextRatio
                + 0.12 * blockConfidence
                + 0.06 * orderScore;
        if (issues.contains(LayoutQualityIssue.NO_TEXT_BLOCKS)) score = Math.min(score, 0.15);
        if (issues.contains(LayoutQualityIssue.CORRUPTED_TEXT)) score -= 0.08;
        score = clamp(score);
        boolean fallback = score < FALLBACK_THRESHOLD
                || issues.contains(LayoutQualityIssue.NO_TEXT_BLOCKS)
                || issues.contains(LayoutQualityIssue.CORRUPTED_TEXT)
                || issues.contains(LayoutQualityIssue.UNSTABLE_READING_ORDER);
        return new LayoutQualityReport(
                score, fallback, characters, geometryRatio, cleanTextRatio,
                blockConfidence, orderScore, List.copyOf(issues));
    }

    private boolean hasValidGeometry(DocumentBlock block) {
        if (block == null || block.bbox() == null || block.page() < 1) return false;
        NormalizedBoundingBox box = block.bbox();
        return Double.isFinite(box.x()) && Double.isFinite(box.y())
                && Double.isFinite(box.width()) && Double.isFinite(box.height())
                && box.x() >= 0 && box.y() >= 0 && box.width() > 0 && box.height() > 0
                && box.right() <= 1.000001 && box.bottom() <= 1.000001;
    }

    private double cleanTextRatio(List<DocumentBlock> blocks) {
        long total = 0;
        long corrupt = 0;
        for (DocumentBlock block : blocks) {
            String text = block.text();
            total += text.codePointCount(0, text.length());
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                if (codePoint == 0xfffd
                        || Character.getType(codePoint) == Character.PRIVATE_USE
                        || (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint))) {
                    corrupt++;
                }
                offset += Character.charCount(codePoint);
            }
        }
        return total == 0 ? 0 : clamp(1 - corrupt / (double) total);
    }

    private ReadingOrderAssessment readingOrderAssessment(List<DocumentBlock> blocks) {
        if (blocks.isEmpty()) return new ReadingOrderAssessment(0, true);

        List<DocumentBlock> ordered = blocks.stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        OrderAudit structural = new OrderAudit();
        for (int index = 0; index < ordered.size(); index++) {
            structural.add(ordered.get(index).readingOrder() != index);
        }

        Map<Integer, List<DocumentBlock>> pages = new LinkedHashMap<>();
        ordered.stream()
                .filter(this::hasValidGeometry)
                .filter(block -> !isNonContentBlock(block))
                .forEach(block -> pages.computeIfAbsent(block.page(), ignored -> new ArrayList<>())
                        .add(block));
        double worstPageOrderScore = 1;
        boolean impossibleLaneTransition = false;
        for (List<DocumentBlock> page : pages.values()) {
            OrderAudit pageOrder = new OrderAudit();
            auditPageOrder(page, pageOrder);
            worstPageOrderScore = Math.min(worstPageOrderScore, pageOrder.score());
            impossibleLaneTransition |= pageOrder.hasViolations();
        }

        double score = Math.min(structural.score(), worstPageOrderScore);
        return new ReadingOrderAssessment(score,
                score < READING_ORDER_ISSUE_THRESHOLD || impossibleLaneTransition);
    }

    /** Audits lane phases without reordering same-lane text or mathematical fragments. */
    private void auditPageOrder(List<DocumentBlock> page, OrderAudit audit) {
        if (!isDoubleColumn(page)) return;

        DocumentBlock previous = null;
        for (DocumentBlock block : page) {
            DocumentLayoutLane lane = block.layoutLane();
            if (lane == DocumentLayoutLane.FULL) {
                previous = null;
                continue;
            }
            if (!isFlowBlock(block)
                    || (lane != DocumentLayoutLane.LEFT && lane != DocumentLayoutLane.RIGHT)) {
                continue;
            }
            if (previous != null && lane != previous.layoutLane()) {
                boolean overlapsPreviousBand = block.bbox().y() <= previous.bbox().bottom() + 0.005;
                audit.add(previous.layoutLane() == DocumentLayoutLane.RIGHT
                        && lane == DocumentLayoutLane.LEFT
                        && overlapsPreviousBand);
            }
            previous = block;
        }
    }

    private boolean isDoubleColumn(List<DocumentBlock> page) {
        long left = page.stream().filter(this::isFlowBlock)
                .filter(block -> block.layoutLane() == DocumentLayoutLane.LEFT).count();
        long right = page.stream().filter(this::isFlowBlock)
                .filter(block -> block.layoutLane() == DocumentLayoutLane.RIGHT).count();
        return left >= 3 && right >= 3;
    }

    private boolean isFlowBlock(DocumentBlock block) {
        if (block.bbox().width() < 0.08) return false;
        return block.text().codePoints().filter(Character::isLetterOrDigit).limit(8).count() >= 8;
    }

    private boolean isNonContentBlock(DocumentBlock block) {
        return block.role() == DocumentBlockRole.HEADER
                || block.role() == DocumentBlockRole.FOOTER
                || block.role() == DocumentBlockRole.MARGIN_METADATA;
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record ReadingOrderAssessment(double score, boolean unstable) {
    }

    private static final class OrderAudit {
        private int opportunities;
        private int violations;

        void add(boolean violation) {
            opportunities++;
            if (violation) violations++;
        }

        double score() {
            return opportunities == 0 ? 1 : 1 - violations / (double) opportunities;
        }

        boolean hasViolations() {
            return violations > 0;
        }
    }
}
