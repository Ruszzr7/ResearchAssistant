package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic quality gate for deciding whether an expensive parser is useful.
 * The score intentionally combines independent signals instead of trusting a
 * parser's self-reported confidence alone.
 */
@Component
public class PaperLayoutQualityAssessor {

    static final double FALLBACK_THRESHOLD = 0.68;

    public LayoutQualityReport assess(PaperLayoutArtifact artifact) {
        if (artifact == null) {
            return new LayoutQualityReport(0, true, 0, 0, 0, 0,
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
        double orderScore = readingOrderScore(blocks);

        List<LayoutQualityIssue> issues = new ArrayList<>();
        if (blocks.isEmpty() || characters == 0) issues.add(LayoutQualityIssue.NO_TEXT_BLOCKS);
        if (charactersPerPage < 100) issues.add(LayoutQualityIssue.SPARSE_TEXT_COVERAGE);
        if (cleanTextRatio < 0.94) issues.add(LayoutQualityIssue.CORRUPTED_TEXT);
        if (geometryRatio < 0.96) issues.add(LayoutQualityIssue.INVALID_GEOMETRY);
        if (blockConfidence < 0.62) issues.add(LayoutQualityIssue.LOW_BLOCK_CONFIDENCE);
        if (orderScore < 0.92) issues.add(LayoutQualityIssue.UNSTABLE_READING_ORDER);
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
                || issues.contains(LayoutQualityIssue.CORRUPTED_TEXT);
        return new LayoutQualityReport(
                score, fallback, characters, geometryRatio, cleanTextRatio,
                blockConfidence, List.copyOf(issues));
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

    private double readingOrderScore(List<DocumentBlock> blocks) {
        if (blocks.isEmpty()) return 0;
        Set<Integer> orders = new HashSet<>();
        int invalid = 0;
        for (DocumentBlock block : blocks) {
            if (block.readingOrder() < 0 || !orders.add(block.readingOrder())) invalid++;
        }
        return clamp(1 - invalid / (double) blocks.size());
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
