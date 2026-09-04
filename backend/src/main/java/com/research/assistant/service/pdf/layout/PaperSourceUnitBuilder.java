package com.research.assistant.service.pdf.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight same-page grouping for formula families, algorithms and visuals. */
public class PaperSourceUnitBuilder {

    private static final Pattern FORMULA_MEMBER = Pattern.compile("^(\\d{1,4})([a-z])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALGORITHM = Pattern.compile(
            "(?i)^\\s*(algorithm|alg\\.)\\s*(\\d+[a-z]?)\\b.*");
    private static final Pattern ALGORITHM_NARRATIVE = Pattern.compile(
            "(?i)^\\s*(?:algorithm|alg\\.)\\s*\\d+[a-z]?\\s+"
                    + "(?:is|uses|ensures|assumes|shows|provides|requires|contains|has|can)\\b.*");
    private static final Pattern ALGORITHM_TITLE = Pattern.compile(
            "(?i)^\\s*(?:algorithm|alg\\.)\\s*\\d+[a-z]?\\s*[:–—-]\\s*\\S.*");
    private static final Pattern PROCEDURAL_SIGNAL = Pattern.compile(
            "(?im)(?:^\\s*\\d{1,2}[.)]|\\b(?:input|output|initialize|repeat|while|return|update|solve|set)\\b|"
                    + "\\bfor\\s+(?:each|all)\\b|\\bend\\s*(?:if|for|while)?\\b)");
    private static final Pattern CAPTION = Pattern.compile(
            "(?i)^\\s*(fig(?:ure)?\\.?|table)\\s*([\\dIVX]+[a-z]?)"
                    + "(?:\\s*[.:–—-]\\s*|\\s+)(.+)");
    private static final Pattern VISUAL_REFERENCE = Pattern.compile(
            "(?i)^\\s*(?:in\\s+)?(?:fig(?:ure)?\\.?|table)\\s*[\\dIVX]+[a-z]?\\s+"
                    + "(?:shows|illustrates|depicts|presents|compares|plots|summarizes|lists)\\b.*");
    private static final Pattern FAMILY_LABEL = Pattern.compile(
            "(?i)^Equations \\(\\d+([a-z])–\\d+([a-z])\\)$");

    public List<PaperSourceUnit> build(PaperLayoutArtifact artifact, List<EquationEntity> equations) {
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
        Map<String, DocumentBlock> byId = new LinkedHashMap<>();
        ordered.forEach(block -> byId.put(block.id(), block));

        List<PaperSourceUnit> result = new ArrayList<>();
        result.addAll(formulaFamilies(equations, byId));
        result.addAll(algorithms(ordered));
        result.addAll(visuals(ordered));
        return result.stream()
                .sorted(Comparator.comparingInt(PaperSourceUnit::page)
                        .thenComparingInt(unit -> unit.blocks().get(0).readingOrder())
                        .thenComparing(PaperSourceUnit::id))
                .toList();
    }

    public List<PaperSourceContinuation> continuations(List<PaperSourceUnit> units) {
        Map<String, List<PaperSourceUnit>> grouped = new LinkedHashMap<>();
        units.stream()
                .filter(unit -> unit.kind() == PaperSourceUnit.Kind.FORMULA_FAMILY
                        || unit.kind() == PaperSourceUnit.Kind.ALGORITHM)
                .forEach(unit -> grouped.computeIfAbsent(continuationKey(unit),
                        ignored -> new ArrayList<>()).add(unit));
        List<PaperSourceContinuation> result = new ArrayList<>();
        for (Map.Entry<String, List<PaperSourceUnit>> entry : grouped.entrySet()) {
            List<PaperSourceUnit> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(PaperSourceUnit::page)).toList();
            List<PaperSourceUnit> current = new ArrayList<>();
            for (PaperSourceUnit unit : sorted) {
                if (!current.isEmpty() && !continues(current.get(current.size() - 1), unit)) {
                    addContinuation(result, entry.getKey(), current);
                    current = new ArrayList<>();
                }
                current.add(unit);
            }
            addContinuation(result, entry.getKey(), current);
        }
        return List.copyOf(result);
    }

    private String continuationKey(PaperSourceUnit unit) {
        return unit.id().replaceFirst(":p\\d+$", "");
    }

    private boolean continues(PaperSourceUnit previous, PaperSourceUnit next) {
        if (next.page() != previous.page() + 1 || previous.kind() != next.kind()) return false;
        if (previous.kind() == PaperSourceUnit.Kind.ALGORITHM) return true;
        Matcher left = FAMILY_LABEL.matcher(previous.label());
        Matcher right = FAMILY_LABEL.matcher(next.label());
        return left.matches() && right.matches()
                && Character.toLowerCase(right.group(1).charAt(0))
                == Character.toLowerCase(left.group(2).charAt(0)) + 1;
    }

    private void addContinuation(List<PaperSourceContinuation> result,
                                 String key,
                                 List<PaperSourceUnit> parts) {
        if (parts.size() < 2) return;
        String label = parts.get(0).kind() == PaperSourceUnit.Kind.FORMULA_FAMILY
                ? combinedFormulaLabel(parts) : parts.get(0).label();
        String text = parts.stream().map(PaperSourceUnit::text)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        result.add(new PaperSourceContinuation("continuation:" + key,
                parts.get(0).kind(), label, text, parts,
                parts.stream().mapToDouble(PaperSourceUnit::confidence).min().orElse(.7)));
    }

    private String combinedFormulaLabel(List<PaperSourceUnit> parts) {
        Matcher first = FAMILY_LABEL.matcher(parts.get(0).label());
        Matcher last = FAMILY_LABEL.matcher(parts.get(parts.size() - 1).label());
        if (!first.matches() || !last.matches()) return parts.get(0).label();
        String base = parts.get(0).id().replaceFirst("^formula-family:", "")
                .replaceFirst(":p\\d+$", "");
        return "Equations (" + base + first.group(1) + "–" + base + last.group(2) + ")";
    }

    private List<PaperSourceUnit> formulaFamilies(List<EquationEntity> equations,
                                                  Map<String, DocumentBlock> byId) {
        Map<String, List<EquationEntity>> grouped = new LinkedHashMap<>();
        for (EquationEntity equation : equations) {
            Matcher matcher = FORMULA_MEMBER.matcher(equation.number());
            if (!matcher.matches()) continue;
            grouped.computeIfAbsent(equation.definition().page() + ":" + matcher.group(1),
                    ignored -> new ArrayList<>()).add(equation);
        }

        List<PaperSourceUnit> result = new ArrayList<>();
        for (List<EquationEntity> family : grouped.values()) {
            family.sort(Comparator.comparingInt(item -> readingOrder(byId, item.definition().blockId())));
            List<EquationEntity> current = new ArrayList<>();
            for (EquationEntity equation : family) {
                if (!current.isEmpty() && !nearby(current.get(current.size() - 1), equation, byId)) {
                    addFormulaFamily(result, current, byId);
                    current = new ArrayList<>();
                }
                current.add(equation);
            }
            addFormulaFamily(result, current, byId);
        }
        return result;
    }

    private boolean nearby(EquationEntity first, EquationEntity second,
                           Map<String, DocumentBlock> byId) {
        DocumentBlock left = byId.get(first.definition().blockId());
        DocumentBlock right = byId.get(second.definition().blockId());
        if (left == null || right == null) return false;
        return right.readingOrder() - left.readingOrder() <= 12
                && verticalGap(first.definition().bbox(), second.definition().bbox()) <= .10
                && compatibleLane(left, right);
    }

    private void addFormulaFamily(List<PaperSourceUnit> result,
                                  List<EquationEntity> family,
                                  Map<String, DocumentBlock> byId) {
        if (family.size() < 2) return;
        List<DocumentBlock> blocks = family.stream().map(item -> byId.get(item.definition().blockId()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (blocks.size() < 2) return;
        List<NormalizedBoundingBox> boxes = family.stream()
                .flatMap(item -> item.definition().boxes().stream()).distinct().toList();
        String first = family.get(0).number();
        String last = family.get(family.size() - 1).number();
        String base = first.replaceFirst("(?i)[a-z]$", "");
        String text = family.stream().map(item -> item.definition().targetText())
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        result.add(new PaperSourceUnit("formula-family:" + base + ":p" + blocks.get(0).page(),
                PaperSourceUnit.Kind.FORMULA_FAMILY,
                "Equations (" + first + "–" + last + ")", blocks.get(0).page(),
                blocks.get(0).sectionPath(), text, blocks, boxes,
                family.stream().mapToDouble(item -> item.definition().confidence()).min().orElse(.7)));
    }

    private List<PaperSourceUnit> algorithms(List<DocumentBlock> ordered) {
        Map<String, PaperSourceUnit> distinct = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            DocumentBlock start = ordered.get(index);
            Matcher matcher = ALGORITHM.matcher(start.text());
            if (!matcher.matches() || ALGORITHM_NARRATIVE.matcher(start.text()).matches()) continue;
            List<DocumentBlock> blocks = algorithmBlocks(ordered, index, start, matcher.group(2));
            PaperSourceUnit candidate = unit("algorithm:" + matcher.group(2) + ":p" + start.page(),
                    PaperSourceUnit.Kind.ALGORITHM, matcher.group(1) + " " + matcher.group(2), blocks);
            if (!isAlgorithmSource(candidate)) continue;
            distinct.merge(candidate.id(), candidate, this::strongerAlgorithmSource);
        }
        List<PaperSourceUnit> result = new ArrayList<>(distinct.values());
        List<PaperSourceUnit> starts = List.copyOf(result);
        for (PaperSourceUnit algorithm : starts) {
            DocumentBlock tail = algorithm.blocks().get(algorithm.blocks().size() - 1);
            if (tail.bbox().bottom() < .84) continue;
            int nextPage = algorithm.page() + 1;
            boolean alreadyPresent = result.stream().anyMatch(unit -> unit.page() == nextPage
                    && unit.label().equalsIgnoreCase(algorithm.label()));
            if (alreadyPresent) continue;
            List<DocumentBlock> continuation = pageTopAlgorithmBlocks(ordered, algorithm, nextPage);
            if (!continuation.isEmpty()) {
                String number = algorithm.id().replaceFirst("^algorithm:", "")
                        .replaceFirst(":p\\d+$", "");
                result.add(unit("algorithm:" + number + ":p" + nextPage,
                        PaperSourceUnit.Kind.ALGORITHM, algorithm.label(), continuation));
            }
        }
        return result;
    }

    private List<DocumentBlock> algorithmBlocks(List<DocumentBlock> ordered,
                                                int startIndex,
                                                DocumentBlock start,
                                                String number) {
        List<DocumentBlock> nearby = new ArrayList<>();
        nearby.add(start);
        for (int next = startIndex + 1; next < ordered.size() && nearby.size() < 32; next++) {
            DocumentBlock candidate = ordered.get(next);
            if (candidate.page() != start.page()
                    || candidate.bbox().bottom() - start.bbox().y() > .45) break;
            Matcher nextAlgorithm = ALGORITHM.matcher(candidate.text());
            if (nextAlgorithm.matches()) {
                if (!nextAlgorithm.group(2).equalsIgnoreCase(number)
                        || !ALGORITHM_NARRATIVE.matcher(candidate.text()).matches()) break;
                continue;
            }
            nearby.add(candidate);
        }

        DocumentLayoutLane contentLane = nearby.stream()
                .filter(block -> proceduralSignalCount(block.text()) > 0)
                .map(DocumentBlock::layoutLane)
                .filter(lane -> lane == DocumentLayoutLane.LEFT || lane == DocumentLayoutLane.RIGHT)
                .collect(java.util.stream.Collectors.groupingBy(lane -> lane,
                        LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(start.layoutLane());

        List<DocumentBlock> result = new ArrayList<>();
        result.add(start);
        for (DocumentBlock candidate : nearby.subList(1, nearby.size())) {
            if (!compatibleWithLane(contentLane, candidate.layoutLane())) continue;
            boolean titleContinuation = candidate.role() == DocumentBlockRole.HEADING
                    && result.size() == 1
                    && verticalGap(start.bbox(), candidate.bbox()) <= .025;
            if (candidate.role() == DocumentBlockRole.HEADING
                    && proceduralSignalCount(candidate.text()) == 0 && !titleContinuation
                    || candidate.role() == DocumentBlockRole.CAPTION
                    || candidate.role() == DocumentBlockRole.FIGURE
                    || candidate.role() == DocumentBlockRole.TABLE) break;
            if (!result.isEmpty()
                    && verticalGap(result.get(result.size() - 1).bbox(), candidate.bbox()) > .06) break;
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private boolean compatibleWithLane(DocumentLayoutLane lane, DocumentLayoutLane candidate) {
        return lane == candidate || lane == DocumentLayoutLane.FULL
                || lane == DocumentLayoutLane.SINGLE || lane == DocumentLayoutLane.UNKNOWN
                || candidate == DocumentLayoutLane.FULL || candidate == DocumentLayoutLane.SINGLE
                || candidate == DocumentLayoutLane.UNKNOWN;
    }

    private boolean isAlgorithmSource(PaperSourceUnit unit) {
        int signals = proceduralSignalCount(unit.text());
        boolean narrativeHeader = ALGORITHM_NARRATIVE.matcher(unit.blocks().get(0).text()).matches();
        boolean captionStyleTitle = ALGORITHM_TITLE.matcher(unit.blocks().get(0).text()).matches();
        return captionStyleTitle || signals >= 2 || signals >= 1 && !narrativeHeader
                && (unit.blocks().size() >= 2 || unit.text().length() >= 60);
    }

    private PaperSourceUnit strongerAlgorithmSource(PaperSourceUnit first, PaperSourceUnit second) {
        return algorithmSourceScore(second) > algorithmSourceScore(first) ? second : first;
    }

    private int algorithmSourceScore(PaperSourceUnit unit) {
        return proceduralSignalCount(unit.text()) * 4
                + Math.min(8, unit.blocks().size())
                + Math.min(4, unit.text().length() / 80);
    }

    private int proceduralSignalCount(String text) {
        Matcher matcher = PROCEDURAL_SIGNAL.matcher(text);
        int count = 0;
        while (matcher.find() && count < 8) count++;
        return count;
    }

    private List<DocumentBlock> pageTopAlgorithmBlocks(List<DocumentBlock> ordered,
                                                       PaperSourceUnit algorithm,
                                                       int page) {
        List<DocumentBlock> candidates = ordered.stream()
                .filter(block -> block.page() == page)
                .filter(block -> block.role() != DocumentBlockRole.HEADER
                        && block.role() != DocumentBlockRole.FOOTER
                        && block.role() != DocumentBlockRole.MARGIN_METADATA)
                .toList();
        if (candidates.isEmpty() || candidates.get(0).bbox().y() > .16
                || !candidates.get(0).sectionPath().equals(algorithm.sectionPath())) return List.of();
        List<DocumentBlock> result = new ArrayList<>();
        for (DocumentBlock candidate : candidates) {
            if (result.size() >= 16 || candidate.bbox().bottom() > .38
                    || candidate.role() == DocumentBlockRole.HEADING
                    || candidate.role() == DocumentBlockRole.CAPTION
                    || candidate.role() == DocumentBlockRole.FIGURE
                    || candidate.role() == DocumentBlockRole.TABLE
                    || ALGORITHM.matcher(candidate.text()).matches()) break;
            if (!result.isEmpty()
                    && verticalGap(result.get(result.size() - 1).bbox(), candidate.bbox()) > .05) break;
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private List<PaperSourceUnit> visuals(List<DocumentBlock> ordered) {
        Map<String, PaperSourceUnit> distinct = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            DocumentBlock caption = ordered.get(index);
            if (VISUAL_REFERENCE.matcher(caption.text()).matches()) continue;
            Matcher matcher = CAPTION.matcher(caption.text());
            if (!matcher.matches()) continue;
            PaperSourceUnit.Kind kind = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("table")
                    ? PaperSourceUnit.Kind.TABLE : PaperSourceUnit.Kind.FIGURE;
            DocumentBlockRole visualRole = kind == PaperSourceUnit.Kind.TABLE
                    ? DocumentBlockRole.TABLE : DocumentBlockRole.FIGURE;
            List<DocumentBlock> blocks = new ArrayList<>();
            nearestVisual(ordered, caption, visualRole).ifPresent(blocks::add);
            blocks.add(caption);
            adjacentExplanation(ordered, index, caption).ifPresent(blocks::add);
            blocks = blocks.stream().distinct().sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
            PaperSourceUnit candidate = unit(kind.name().toLowerCase(Locale.ROOT) + ":" + matcher.group(2)
                            + ":p" + caption.page(), kind,
                    matcher.group(1) + " " + matcher.group(2), blocks);
            String visualKey = kind == PaperSourceUnit.Kind.FIGURE
                    ? kind.name() + ":" + matcher.group(2).toLowerCase(Locale.ROOT)
                    : candidate.id();
            distinct.merge(visualKey, candidate, this::strongerVisualSource);
        }
        return List.copyOf(distinct.values());
    }

    private PaperSourceUnit strongerVisualSource(PaperSourceUnit first, PaperSourceUnit second) {
        return visualSourceScore(second) > visualSourceScore(first) ? second : first;
    }

    private int visualSourceScore(PaperSourceUnit unit) {
        DocumentBlock caption = unit.blocks().stream()
                .filter(block -> CAPTION.matcher(block.text()).matches())
                .findFirst().orElse(unit.blocks().get(0));
        int score = caption.role() == DocumentBlockRole.CAPTION ? 4 : 0;
        score += (int) unit.blocks().stream()
                .filter(block -> block.role() == DocumentBlockRole.FIGURE
                        || block.role() == DocumentBlockRole.TABLE).count() * 3;
        return score + Math.min(3, unit.blocks().size())
                + Math.min(2, caption.text().length() / 80);
    }

    private java.util.Optional<DocumentBlock> nearestVisual(List<DocumentBlock> ordered,
                                                            DocumentBlock caption,
                                                            DocumentBlockRole role) {
        return ordered.stream().filter(block -> block.page() == caption.page() && block.role() == role)
                .filter(block -> compatibleLane(caption, block))
                .filter(block -> verticalGap(caption.bbox(), block.bbox()) <= .12)
                .min(Comparator.comparingDouble(block -> verticalGap(caption.bbox(), block.bbox())));
    }

    private java.util.Optional<DocumentBlock> adjacentExplanation(List<DocumentBlock> ordered,
                                                                  int captionIndex,
                                                                  DocumentBlock caption) {
        return java.util.stream.IntStream.of(captionIndex - 1, captionIndex + 1)
                .filter(index -> index >= 0 && index < ordered.size())
                .mapToObj(ordered::get)
                .filter(block -> block.page() == caption.page() && block.role() == DocumentBlockRole.BODY)
                .filter(block -> VISUAL_REFERENCE.matcher(block.text()).matches())
                .filter(block -> compatibleLane(caption, block))
                .filter(block -> verticalGap(caption.bbox(), block.bbox()) <= .05)
                .findFirst();
    }

    private PaperSourceUnit unit(String id, PaperSourceUnit.Kind kind, String label,
                                 List<DocumentBlock> blocks) {
        String text = blocks.stream().map(DocumentBlock::text).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return new PaperSourceUnit(id, kind, label, blocks.get(0).page(),
                blocks.get(0).sectionPath(), text, blocks,
                blocks.stream().map(DocumentBlock::bbox).distinct().toList(),
                blocks.stream().mapToDouble(DocumentBlock::confidence).min().orElse(.7));
    }

    private int readingOrder(Map<String, DocumentBlock> byId, String blockId) {
        DocumentBlock block = byId.get(blockId);
        return block == null ? Integer.MAX_VALUE : block.readingOrder();
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

    private double verticalGap(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first.bottom() < second.y()) return second.y() - first.bottom();
        if (second.bottom() < first.y()) return first.y() - second.bottom();
        return 0;
    }
}
