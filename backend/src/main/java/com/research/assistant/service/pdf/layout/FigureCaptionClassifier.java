package com.research.assistant.service.pdf.layout;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared structural classification for figure captions and figure discussions. */
final class FigureCaptionClassifier {

    private static final double MAX_VISUAL_GAP = .06;
    private static final Pattern FIGURE_LEAD = Pattern.compile(
            "(?i)^\\s*(fig(?:ure)?\\.?)\\s*([\\dIVX]+[a-z]?)"
                    + "(?:\\s*([.:–—-])\\s*|\\s+)(\\S.*)$");

    Optional<Caption> caption(DocumentBlock block, List<DocumentBlock> pageBlocks) {
        if (block == null) return Optional.empty();
        Matcher matcher = FIGURE_LEAD.matcher(block.text() == null ? "" : block.text());
        if (!matcher.matches()) return Optional.empty();
        boolean explicitSeparator = matcher.group(3) != null;
        if (!explicitSeparator && !hasIndependentVisualAbove(block, pageBlocks)) {
            return Optional.empty();
        }
        return Optional.of(new Caption(matcher.group(1), matcher.group(2), explicitSeparator));
    }

    boolean isDiscussion(DocumentBlock block, List<DocumentBlock> pageBlocks) {
        return hasFigureLead(block) && caption(block, pageBlocks).isEmpty();
    }

    boolean hasFigureLead(DocumentBlock block) {
        return block != null && FIGURE_LEAD.matcher(
                block.text() == null ? "" : block.text()).matches();
    }

    boolean isCaptionContinuation(DocumentBlock block, List<DocumentBlock> pageBlocks) {
        return block != null && caption(block, pageBlocks).isEmpty()
                && owningCaption(block, pageBlocks, new HashSet<>(), 0).isPresent();
    }

    private Optional<Caption> owningCaption(DocumentBlock block, List<DocumentBlock> pageBlocks,
                                            Set<String> visited, int depth) {
        Optional<Caption> direct = caption(block, pageBlocks);
        if (direct.isPresent()) return direct;
        if (block == null || pageBlocks == null || depth >= 5 || hasFigureLead(block)
                || !continuationRole(block.role()) || block.bbox() == null
                || !visited.add(block.id())) return Optional.empty();
        return pageBlocks.stream()
                .filter(candidate -> candidate != null && candidate != block && candidate.bbox() != null)
                .filter(candidate -> candidate.page() == block.page())
                .filter(candidate -> candidate.readingOrder() < block.readingOrder())
                .filter(candidate -> compatibleLane(block, candidate))
                .filter(candidate -> candidate.sectionPath().equals(block.sectionPath()))
                .filter(candidate -> Math.abs(candidate.bbox().x() - block.bbox().x()) <= .035)
                .filter(candidate -> horizontalOverlap(candidate.bbox(), block.bbox()) >= .5)
                .filter(candidate -> candidate.bbox().bottom() <= block.bbox().y() + .003)
                .filter(candidate -> verticalGap(candidate.bbox(), block.bbox()) <= .014)
                .filter(candidate -> block.bbox().height()
                        <= Math.max(.022, candidate.bbox().height() * 2.5))
                .sorted(Comparator.comparingDouble((DocumentBlock candidate) ->
                                verticalGap(candidate.bbox(), block.bbox()))
                        .thenComparing(Comparator.comparingInt(DocumentBlock::readingOrder).reversed()))
                .map(candidate -> owningCaption(candidate, pageBlocks,
                        new HashSet<>(visited), depth + 1))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean continuationRole(DocumentBlockRole role) {
        return role == DocumentBlockRole.CAPTION
                || role == DocumentBlockRole.BODY
                || role == DocumentBlockRole.FORMULA;
    }

    private boolean hasIndependentVisualAbove(DocumentBlock caption,
                                              List<DocumentBlock> pageBlocks) {
        if (pageBlocks == null || caption.bbox() == null) return false;
        return pageBlocks.stream()
                .filter(candidate -> candidate != null && candidate != caption)
                .filter(candidate -> candidate.page() == caption.page())
                .filter(candidate -> candidate.role() == DocumentBlockRole.FIGURE)
                .filter(candidate -> candidate.contentMode() == DocumentBlockContentMode.REGION)
                .filter(candidate -> compatibleLane(caption, candidate))
                .filter(candidate -> candidate.bbox() != null
                        && candidate.bbox().bottom() <= caption.bbox().y() + .006)
                .filter(candidate -> verticalGap(candidate.bbox(), caption.bbox()) <= MAX_VISUAL_GAP)
                .anyMatch(candidate -> horizontalOverlap(candidate.bbox(), caption.bbox()) >= .5);
    }

    private boolean compatibleLane(DocumentBlock first, DocumentBlock second) {
        return first.layoutLane() == second.layoutLane()
                || first.layoutLane() == DocumentLayoutLane.FULL
                || second.layoutLane() == DocumentLayoutLane.FULL
                || first.layoutLane() == DocumentLayoutLane.SINGLE
                || second.layoutLane() == DocumentLayoutLane.SINGLE
                || first.layoutLane() == DocumentLayoutLane.UNKNOWN
                || second.layoutLane() == DocumentLayoutLane.UNKNOWN;
    }

    private double horizontalOverlap(NormalizedBoundingBox first,
                                     NormalizedBoundingBox second) {
        double overlap = Math.max(0, Math.min(first.right(), second.right())
                - Math.max(first.x(), second.x()));
        return overlap / Math.max(.001, Math.min(first.width(), second.width()));
    }

    private double verticalGap(NormalizedBoundingBox first,
                               NormalizedBoundingBox second) {
        if (first.bottom() < second.y()) return second.y() - first.bottom();
        if (second.bottom() < first.y()) return first.y() - second.bottom();
        return 0;
    }

    record Caption(String marker, String number, boolean explicitSeparator) {
        String label() {
            return marker + " " + number;
        }
    }
}
