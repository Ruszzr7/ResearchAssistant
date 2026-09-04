package com.research.assistant.service.pdf.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds lightweight display-formula contexts without changing the atomic PDF blocks.
 *
 * <p>The parser intentionally emits visual lines. This builder only adds the logical
 * relation needed by evidence and visual recovery: several adjacent formula lines can
 * share one complete display context while their original coordinates remain available.</p>
 */
public class FormulaContextBuilder {

    private static final Pattern EQUATION_LABEL = Pattern.compile("(?<![A-Za-z0-9_])\\((\\d{1,4}[a-z]?)\\)\\s*[.,;:]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL_ONLY = Pattern.compile("^\\(\\d{1,4}[a-z]?\\)$",
            Pattern.CASE_INSENSITIVE);
    private static final double MIN_LABELED_GAP = .024;
    private static final double MAX_LABELED_GAP = .075;
    private static final double MIN_UNLABELED_GAP = .022;
    private static final double MAX_UNLABELED_GAP = .050;
    private static final Set<String> MATH_WORDS = Set.of(
            "arccos", "arcsin", "arctan", "cos", "det", "exp", "log", "ln", "max", "min", "rank", "sin");

    public List<FormulaContext> build(PaperLayoutArtifact artifact) {
        if (artifact == null || artifact.blocks().isEmpty()) return List.of();
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .filter(block -> block != null && block.bbox() != null)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        List<DocumentBlock> formulaBlocks = ordered.stream()
                .filter(this::formulaLike)
                .toList();
        List<FormulaContext> result = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();
        // Numbered display formulas are stronger anchors, so claim their adjacent
        // unnumbered lines before handling unnumbered formula runs.
        List<DocumentBlock> seeds = new ArrayList<>();
        seeds.addAll(formulaBlocks.stream().filter(this::hasEquationLabel).toList());
        seeds.addAll(formulaBlocks.stream().filter(block -> !hasEquationLabel(block)).toList());
        for (DocumentBlock seed : seeds) {
            if (consumed.contains(seed.id())) continue;
            List<DocumentBlock> group = expand(seed, formulaBlocks, ordered, consumed);
            group.forEach(block -> consumed.add(block.id()));
            result.add(context(group));
        }
        return result.stream()
                .sorted(Comparator.comparingInt(FormulaContext::page)
                        .thenComparingInt(context -> context.blocks().get(0).readingOrder()))
                .toList();
    }

    public Optional<FormulaContext> find(List<FormulaContext> contexts,
                                         DocumentBlock anchor,
                                         String equationNumber) {
        if (contexts == null || anchor == null) return Optional.empty();
        return contexts.stream()
                .filter(context -> context.blocks().stream()
                        .anyMatch(block -> block.id().equals(anchor.id())))
                .filter(context -> equationNumber == null || equationNumber.isBlank()
                        || context.equationNumbers().contains(equationNumber))
                .findFirst();
    }

    private List<DocumentBlock> expand(DocumentBlock seed,
                                       List<DocumentBlock> candidates,
                                       List<DocumentBlock> allBlocks,
                                       Set<String> consumed) {
        List<DocumentBlock> group = new ArrayList<>();
        group.add(seed);
        while (true) {
            DocumentBlock next = candidates.stream()
                    .filter(candidate -> candidate.page() == seed.page())
                    .filter(candidate -> !consumed.contains(candidate.id()))
                    .filter(candidate -> group.stream()
                            .noneMatch(member -> member.id().equals(candidate.id())))
                    .filter(candidate -> group.stream()
                            .anyMatch(member -> canJoin(member, candidate, group)
                                    && !hasProseBarrier(member, candidate, allBlocks)))
                    .min(Comparator.<DocumentBlock>comparingDouble(candidate -> distanceToGroup(candidate, group))
                            .thenComparingInt(DocumentBlock::readingOrder))
                    .orElse(null);
            if (next == null) break;
            group.add(next);
        }
        group.sort(Comparator.comparingInt(DocumentBlock::readingOrder));
        return List.copyOf(group);
    }

    private boolean canJoin(DocumentBlock left,
                            DocumentBlock right,
                            List<DocumentBlock> current) {
        if (left.page() != right.page() || !compatibleLane(left.layoutLane(), right.layoutLane())) {
            return false;
        }
        if (conflictingLabels(current, left) || conflictingLabels(current, right)) return false;
        if (!horizontalCompatible(left, right)) return false;
        double gap = verticalGap(left.bbox(), right.bbox());
        boolean labeled = current.stream().anyMatch(this::hasEquationLabel)
                || hasEquationLabel(right);
        double height = Math.max(left.bbox().height(), right.bbox().height());
        double limit = labeled
                ? Math.min(MAX_LABELED_GAP, Math.max(MIN_LABELED_GAP, height * 4.0))
                : Math.min(MAX_UNLABELED_GAP, Math.max(MIN_UNLABELED_GAP, height * 3.0));
        return gap <= limit;
    }

    private boolean conflictingLabels(List<DocumentBlock> current, DocumentBlock candidate) {
        Set<String> currentLabels = current.stream()
                .flatMap(block -> equationLabels(block).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> candidateLabels = equationLabels(candidate);
        return !currentLabels.isEmpty() && !candidateLabels.isEmpty()
                && currentLabels.stream().noneMatch(candidateLabels::contains);
    }

    private boolean horizontalCompatible(DocumentBlock first, DocumentBlock second) {
        if (LABEL_ONLY.matcher(first.text().replaceAll("\\s+", "").trim()).matches()
                || LABEL_ONLY.matcher(second.text().replaceAll("\\s+", "").trim()).matches()) {
            return true;
        }
        NormalizedBoundingBox left = first.bbox();
        NormalizedBoundingBox right = second.bbox();
        double overlap = Math.max(0,
                Math.min(left.right(), right.right()) - Math.max(left.x(), right.x()));
        double smallerWidth = Math.max(.001, Math.min(left.width(), right.width()));
        double firstCenter = left.x() + left.width() / 2.0;
        double secondCenter = right.x() + right.width() / 2.0;
        return overlap / smallerWidth >= .12 || Math.abs(firstCenter - secondCenter) <= .12;
    }

    private double distanceToGroup(DocumentBlock candidate, List<DocumentBlock> group) {
        return group.stream()
                .mapToDouble(member -> verticalGap(member.bbox(), candidate.bbox())
                        + horizontalGap(member.bbox(), candidate.bbox()))
                .min().orElse(Double.MAX_VALUE);
    }

    private double horizontalGap(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first.right() >= second.x() && second.right() >= first.x()) return 0;
        return first.right() < second.x() ? second.x() - first.right() : first.x() - second.right();
    }

    private boolean hasProseBarrier(DocumentBlock first,
                                    DocumentBlock second,
                                    List<DocumentBlock> allBlocks) {
        if (first.page() != second.page()) return true;
        double top = Math.min(first.bbox().y(), second.bbox().y());
        double bottom = Math.max(first.bbox().bottom(), second.bbox().bottom());
        NormalizedBoundingBox corridor = new NormalizedBoundingBox(
                Math.min(first.bbox().x(), second.bbox().x()), top,
                Math.max(first.bbox().right(), second.bbox().right())
                        - Math.min(first.bbox().x(), second.bbox().x()),
                Math.max(.001, bottom - top));
        return allBlocks.stream()
                .filter(block -> !block.id().equals(first.id()) && !block.id().equals(second.id()))
                .filter(block -> block.page() == first.page() && block.bbox() != null)
                .filter(block -> block.bbox().y() <= bottom && block.bbox().bottom() >= top)
                .filter(block -> compatibleLane(block.layoutLane(), first.layoutLane())
                        || compatibleLane(block.layoutLane(), second.layoutLane()))
                .filter(block -> horizontalGap(block.bbox(), corridor) <= .02)
                .anyMatch(block -> !formulaLike(block));
    }

    private boolean formulaLike(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.FORMULA) {
            return block.latex() != null && !block.latex().isBlank()
                    || compactMath(block.text());
        }
        if (isFormulaSupport(block)) return true;
        if (block.role() != DocumentBlockRole.BODY || block.mathProfile() == null
                || block.mathProfile().signalCount() == 0 && !compactMath(block.text())
                || block.mathProfile().signalCount() > 0 && block.mathProfile().density() < .10
                        && !compactMath(block.text())) {
            return false;
        }
        Matcher prose = Pattern.compile("[A-Za-z]{4,}").matcher(block.text());
        while (prose.find()) {
            if (!MATH_WORDS.contains(prose.group().toLowerCase())) return false;
        }
        return true;
    }

    private boolean isFormulaSupport(DocumentBlock block) {
        if ((block.role() != DocumentBlockRole.BODY && block.role() != DocumentBlockRole.HEADER)
                || block.bbox() == null) return false;
        if (block.role() == DocumentBlockRole.HEADER && block.bbox().height() > .012) return false;
        String source = block.text() == null ? "" : block.text().trim();
        String raw = source.replaceAll("\\s+", "");
        if (raw.length() > 32) return false;
        Matcher prose = Pattern.compile("[A-Za-z]{4,}").matcher(source);
        while (prose.find()) {
            if (!MATH_WORDS.contains(prose.group().toLowerCase())) return false;
        }
        long letters = raw.codePoints().filter(Character::isLetter).count();
        boolean hasSymbol = raw.codePoints().anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint));
        boolean shortTokens = Pattern.compile("[A-Za-z]+")
                .matcher(source).results().allMatch(match -> match.group().length() <= 3);
        return compactMath(raw) || (letters <= 8 && raw.length() <= 16 && (hasSymbol || shortTokens));
    }

    private boolean compactMath(String text) {
        String raw = text == null ? "" : text.trim();
        String value = raw.replaceAll("\\s+", "");
        if (value.length() > 96) return false;
        Matcher prose = Pattern.compile("[A-Za-z]{4,}").matcher(raw);
        while (prose.find()) {
            if (!MATH_WORDS.contains(prose.group().toLowerCase())) return false;
        }
        return value.isBlank()
                || value.codePoints().anyMatch(codePoint -> Character.isDigit(codePoint)
                || "=+−-*/<>≤≥∑∏√∞()[]{}|,.:?".indexOf(codePoint) >= 0);
    }

    private boolean hasEquationLabel(DocumentBlock block) {
        return !equationLabels(block).isEmpty();
    }

    private Set<String> equationLabels(DocumentBlock block) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher matcher = EQUATION_LABEL.matcher(block.text());
        while (matcher.find()) labels.add(matcher.group(1));
        return labels;
    }

    private FormulaContext context(List<DocumentBlock> blocks) {
        Set<String> labels = blocks.stream()
                .flatMap(block -> equationLabels(block).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new FormulaContext(
                "formula-context:p" + blocks.get(0).page() + "-" + blocks.get(0).id(),
                blocks.get(0).page(), blocks.get(0).layoutLane(), blocks,
                List.copyOf(labels), union(blocks),
                blocks.stream().mapToDouble(DocumentBlock::confidence).min().orElse(.7));
    }

    private boolean compatibleLane(DocumentLayoutLane first, DocumentLayoutLane second) {
        if (first == second) return true;
        return first == DocumentLayoutLane.FULL || second == DocumentLayoutLane.FULL;
    }

    private double verticalGap(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first.bottom() < second.y()) return second.y() - first.bottom();
        if (second.bottom() < first.y()) return first.y() - second.bottom();
        return 0;
    }

    private NormalizedBoundingBox union(List<DocumentBlock> blocks) {
        double left = blocks.stream().mapToDouble(block -> block.bbox().x()).min().orElse(0);
        double top = blocks.stream().mapToDouble(block -> block.bbox().y()).min().orElse(0);
        double right = blocks.stream().mapToDouble(block -> block.bbox().right()).max().orElse(left);
        double bottom = blocks.stream().mapToDouble(block -> block.bbox().bottom()).max().orElse(top);
        return new NormalizedBoundingBox(left, top, right - left, bottom - top);
    }

    public record FormulaContext(String id,
                                 int page,
                                 DocumentLayoutLane lane,
                                 List<DocumentBlock> blocks,
                                 List<String> equationNumbers,
                                 NormalizedBoundingBox bbox,
                                 double confidence) {
        public FormulaContext {
            id = id == null ? "" : id;
            lane = lane == null ? DocumentLayoutLane.UNKNOWN : lane;
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            equationNumbers = equationNumbers == null ? List.of() : List.copyOf(equationNumbers);
            confidence = Math.max(0, Math.min(1, confidence));
        }

        public String text() {
            return blocks.stream().map(DocumentBlock::text)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
        }
    }
}
