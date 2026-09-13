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

    private static final int MAX_CAPTION_BLOCKS = 6;
    private static final double COLUMN_BOUNDARY = .5;
    private static final double COLUMN_TOLERANCE = .012;
    private static final double MAX_CAPTION_LINE_GAP = .014;
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
    private static final Pattern MULTI_FIGURE_REFERENCE = Pattern.compile(
            "(?i)^\\s*fig(?:ure)?\\.?\\s*[\\dIVX]+[a-z]?\\s+and\\s+"
                    + "fig(?:ure)?\\.?\\s*[\\dIVX]+[a-z]?\\s*,?\\s*"
                    + "(?:respectively|shows|illustrates|depicts|presents|compares|plots)\\b.*");
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
            if (blocks.isEmpty()) continue;
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
        // Scan the complete remainder of the page before choosing the content
        // lane.  Reading order on a two-column PDF interleaves the columns, so
        // a fixed distance from the title can truncate a long algorithm even
        // though all of its blocks are still on the same physical page.
        for (int next = startIndex + 1; next < ordered.size(); next++) {
            DocumentBlock candidate = ordered.get(next);
            if (candidate.page() != start.page()) break;
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
        if (compatibleWithLane(contentLane, start)) result.add(start);
        for (DocumentBlock candidate : nearby.subList(1, nearby.size())) {
            boolean titleContinuation = candidate.role() == DocumentBlockRole.HEADING
                    && result.size() == 1
                    && verticalGap(start.bbox(), candidate.bbox()) <= .025;
            // A malformed line can be labelled FULL when PDF extraction joined a
            // left-column algorithm line with prose from the right column. Such a
            // block is not a valid algorithm member: accepting it would both leak
            // unrelated text into the evidence and make the client highlight span
            // across the gutter. A genuine full-width algorithm remains supported
            // when the start and procedural members themselves are FULL/SINGLE.
            if (!compatibleWithLane(contentLane, candidate)) continue;
            if (candidate.role() == DocumentBlockRole.HEADING
                    && proceduralSignalCount(candidate.text()) == 0 && !titleContinuation
                    || candidate.role() == DocumentBlockRole.CAPTION
                    || candidate.role() == DocumentBlockRole.FIGURE
                    || candidate.role() == DocumentBlockRole.TABLE) break;
            if (!result.isEmpty()) {
                DocumentBlock previous = result.get(result.size() - 1);
                double gap = verticalGap(previous.bbox(), candidate.bbox());
                if (gap > .06 || isNarrativeAfterProcedure(result, candidate, gap)) break;
            }
            result.add(candidate);
        }
        return List.copyOf(result);
    }

    private boolean isNarrativeAfterProcedure(List<DocumentBlock> procedure,
                                              DocumentBlock candidate,
                                              double verticalGap) {
        if (verticalGap <= .025 || candidate.role() != DocumentBlockRole.BODY
                || proceduralSignalCount(candidate.text()) > 0) return false;
        int signals = procedure.stream().mapToInt(block -> proceduralSignalCount(block.text())).sum();
        if (signals < 2) return false;
        String text = candidate.text() == null ? "" : candidate.text().trim();
        long proseWords = Pattern.compile("[A-Za-z]{4,}").matcher(text).results().limit(8).count();
        return proseWords >= 5 && candidate.bbox().width() >= .20;
    }

    private boolean compatibleWithLane(DocumentLayoutLane lane, DocumentBlock candidate) {
        DocumentLayoutLane candidateLane = candidate.layoutLane();
        if (lane == candidateLane || lane == DocumentLayoutLane.FULL
                || lane == DocumentLayoutLane.SINGLE || lane == DocumentLayoutLane.UNKNOWN) {
            return true;
        }
        if (candidateLane == DocumentLayoutLane.LEFT || candidateLane == DocumentLayoutLane.RIGHT) {
            return false;
        }
        if (lane == DocumentLayoutLane.LEFT) {
            return candidate.bbox().right() <= COLUMN_BOUNDARY + COLUMN_TOLERANCE;
        }
        if (lane == DocumentLayoutLane.RIGHT) {
            return candidate.bbox().x() >= COLUMN_BOUNDARY - COLUMN_TOLERANCE;
        }
        // SINGLE/UNKNOWN/FULL candidates are only ambiguous when their physical
        // rectangle crosses the central gutter. Keep same-column sparse fixtures
        // usable while rejecting that cross-column extraction artifact.
        return true;
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
            if (isVisualReference(caption.text())) continue;
            Matcher matcher = CAPTION.matcher(caption.text());
            if (!matcher.matches()) continue;
            PaperSourceUnit.Kind kind = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("table")
                    ? PaperSourceUnit.Kind.TABLE : PaperSourceUnit.Kind.FIGURE;
            DocumentBlockRole visualRole = kind == PaperSourceUnit.Kind.TABLE
                    ? DocumentBlockRole.TABLE : DocumentBlockRole.FIGURE;
            List<DocumentBlock> blocks = new ArrayList<>();
            java.util.Optional<DocumentBlock> visual = nearestVisual(ordered, caption, visualRole);
            if (visual.isEmpty() && kind == PaperSourceUnit.Kind.FIGURE) {
                visual = inferredFigureRegion(ordered, caption);
            }
            visual.ifPresent(blocks::add);
            if (kind == PaperSourceUnit.Kind.FIGURE) {
                blocks.addAll(figureCaptionBlocks(ordered, index, caption));
            } else {
                blocks.add(caption);
                adjacentExplanation(ordered, index, caption).ifPresent(blocks::add);
            }
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
                .filter(block -> CAPTION.matcher(block.text()).matches()
                        && !isVisualReference(block.text()))
                .findFirst().orElse(unit.blocks().get(0));
        int score = caption.role() == DocumentBlockRole.CAPTION ? 4 : 0;
        score += (int) unit.blocks().stream()
                .filter(block -> block.role() == DocumentBlockRole.FIGURE
                        || block.role() == DocumentBlockRole.TABLE).count() * 3;
        return score + Math.min(3, unit.blocks().size())
                + Math.min(2, caption.text().length() / 80);
    }

    private boolean isVisualReference(String text) {
        return VISUAL_REFERENCE.matcher(text == null ? "" : text).matches()
                || MULTI_FIGURE_REFERENCE.matcher(text == null ? "" : text).matches();
    }

    /**
     * PDF text extraction commonly splits one printed figure caption into several
     * adjacent blocks, and mathematical subscripts may be classified as BODY or
     * FORMULA.  Reassemble only the immediately adjacent same-lane blocks.  The
     * first layout gap, new caption or explanatory figure-reference sentence ends
     * the caption, so nearby analysis remains an independent TEXT source.
     */
    private List<DocumentBlock> figureCaptionBlocks(List<DocumentBlock> ordered,
                                                    int captionIndex,
                                                    DocumentBlock caption) {
        List<DocumentBlock> result = new ArrayList<>();
        result.add(caption);
        DocumentBlock previous = caption;
        for (int next = captionIndex + 1;
             next < ordered.size() && result.size() < MAX_CAPTION_BLOCKS;
             next++) {
            DocumentBlock candidate = ordered.get(next);
            if (candidate.page() != caption.page()
                    || !compatibleLane(caption, candidate)
                    || CAPTION.matcher(candidate.text()).matches()
                    || isVisualReference(candidate.text())
                    || !captionContinuationRole(candidate.role())
                    || verticalGap(previous.bbox(), candidate.bbox()) > MAX_CAPTION_LINE_GAP) break;
            result.add(candidate);
            previous = candidate;
        }
        return List.copyOf(result);
    }

    private boolean captionContinuationRole(DocumentBlockRole role) {
        return role == DocumentBlockRole.CAPTION
                || role == DocumentBlockRole.BODY
                || role == DocumentBlockRole.FORMULA;
    }

    private java.util.Optional<DocumentBlock> nearestVisual(List<DocumentBlock> ordered,
                                                            DocumentBlock caption,
                                                            DocumentBlockRole role) {
        return ordered.stream().filter(block -> block.page() == caption.page() && block.role() == role)
                .filter(block -> compatibleLane(caption, block))
                .filter(block -> verticalGap(caption.bbox(), block.bbox()) <= .12)
                .min(Comparator.comparingDouble(block -> verticalGap(caption.bbox(), block.bbox())));
    }

    /**
     * PDFBox can read a figure caption while exposing no FIGURE block, especially for
     * vector charts.  In that case preserve the caption as the semantic index and add
     * one conservative same-column region above it for visual inspection.  This is a
     * locator fallback only; it does not invent searchable text or interpret the image.
     */
    private java.util.Optional<DocumentBlock> inferredFigureRegion(List<DocumentBlock> ordered,
                                                                    DocumentBlock caption) {
        NormalizedBoundingBox captionBox = caption.bbox();
        double laneLeft;
        double laneRight;
        if (caption.layoutLane() == DocumentLayoutLane.LEFT) {
            laneLeft = .04;
            laneRight = .49;
        } else if (caption.layoutLane() == DocumentLayoutLane.RIGHT) {
            laneLeft = .51;
            laneRight = .96;
        } else {
            laneLeft = Math.max(.03, captionBox.x() - .02);
            laneRight = Math.min(.97, captionBox.right() + .02);
        }

        double lowerBound = Math.max(.04, captionBox.y() - .34);
        double precedingProseBottom = ordered.stream()
                .filter(block -> block.page() == caption.page())
                .filter(block -> compatibleLane(caption, block))
                .filter(block -> block.bbox().bottom() <= captionBox.y())
                .filter(block -> block.bbox().right() > laneLeft && block.bbox().x() < laneRight)
                .filter(block -> block.role() == DocumentBlockRole.BODY
                        || block.role() == DocumentBlockRole.ABSTRACT
                        || block.role() == DocumentBlockRole.HEADING)
                .filter(block -> block.text().strip().length() >= 40)
                .mapToDouble(block -> block.bbox().bottom())
                .filter(bottom -> bottom < captionBox.y() - .06)
                .max().orElse(lowerBound);
        double top = Math.max(lowerBound, precedingProseBottom + .008);
        double bottom = captionBox.y() - .006;
        if (bottom - top < .06 || laneRight - laneLeft < .12) return java.util.Optional.empty();

        NormalizedBoundingBox box = new NormalizedBoundingBox(
                laneLeft, top, laneRight - laneLeft, bottom - top);
        return java.util.Optional.of(new DocumentBlock(
                caption.id() + ":visual-region", caption.page(), box,
                DocumentBlockRole.FIGURE, Math.max(0, caption.readingOrder() - 1),
                caption.sectionPath(), "", null, null,
                Math.min(.75, caption.confidence()), DocumentBlockContentMode.REGION,
                MathContentProfile.none(""), caption.layoutLane()));
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
