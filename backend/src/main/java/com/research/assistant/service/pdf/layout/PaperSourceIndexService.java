package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds trusted text/formula addresses without asking the LLM to invent coordinates. */
@Service
public class PaperSourceIndexService {

    private static final Pattern EQUATION = Pattern.compile("\\((\\d{1,4}[a-z]?)\\)");
    private static final Pattern THEOREM = Pattern.compile(
            "(?i)\\b(theorem|lemma|proposition|corollary)\\s+(\\d+[a-z]?)\\b");
    private static final Pattern PROOF = Pattern.compile("(?i)\\bproof\\b");
    private static final Pattern MENTION_AT_END = Pattern.compile(
            "(?i).*(?:in|from|using|by|see|shown\\s+in|calculated\\s+by|given\\s+in|"
                    + "equation|eq\\.)\\s*\\(\\d{1,4}[a-z]?\\)\\s*[.,;:]?\\s*$");
    private static final Pattern FORMULA_REFERENCE = Pattern.compile(
            "(?i)\\b(?:in|from|using|by|see|shown\\s+in|calculated\\s+by|given\\s+in|"
                    + "equation|eq(?:uation)?\\.?)\\s*\\(\\d{1,4}[a-z]?\\)");
    private final PaperSourceUnitBuilder sourceUnitBuilder = new PaperSourceUnitBuilder();
    private final FormulaContextBuilder formulaContextBuilder = new FormulaContextBuilder();

    public PaperSourceIndex build(PaperLayoutArtifact artifact) {
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
        List<FormulaContextBuilder.FormulaContext> formulaContexts = formulaContextBuilder.build(artifact);
        List<FormulaLabel> definitionLabels = definitionLabels(ordered);
        List<SourceAnchor> textAnchors = ordered.stream()
                .filter(block -> block.contentMode() == DocumentBlockContentMode.TEXT)
                .map(block -> anchor(artifact, block, SourceAnchor.Kind.TEXT_RANGE,
                        block.text(), block.bbox(), block.confidence()))
                .toList();

        Map<String, MutableEquation> equations = new LinkedHashMap<>();
        List<StatementOwner> statementOwners = statementOwners(ordered);
        for (DocumentBlock block : ordered) {
            Matcher matcher = EQUATION.matcher(block.text());
            while (matcher.find()) {
                String number = matcher.group(1);
                if (isDefinition(ordered, block, matcher.start())) {
                    SourceAnchor definition = formulaAnchor(artifact, ordered, block, number,
                            formulaContexts, definitionLabels);
                    StatementOwner owner = nearestOwner(ordered, statementOwners, block);
                    EquationEntity.Relation relation = owner == null
                            ? EquationEntity.Relation.OTHER
                            : proofBetween(ordered, owner.block(), block)
                            ? EquationEntity.Relation.PROOF_STEP
                            : EquationEntity.Relation.THEOREM_RESULT;
                    equations.compute(number, (ignored, current) -> chooseDefinition(
                            current, number, definition, relation,
                            owner == null ? "" : owner.kind(),
                            owner == null ? "" : owner.number(),
                            nearbyContext(ordered, block)));
                } else {
                    MutableEquation current = equations.computeIfAbsent(number,
                            ignored -> new MutableEquation(number));
                    current.mentions.add(anchor(artifact, block, SourceAnchor.Kind.TEXT_RANGE,
                            block.text(), block.bbox(), block.confidence()));
                }
            }
        }
        List<EquationEntity> result = equations.values().stream()
                .filter(value -> value.definition != null)
                .map(MutableEquation::freeze)
                .sorted(Comparator.comparingInt((EquationEntity value) -> value.definition().page())
                        .thenComparingDouble(value -> value.definition().bbox().y()))
                .toList();
        List<PaperSourceUnit> sourceUnits = sourceUnitBuilder.build(artifact, result);
        List<PaperSourceContinuation> continuations = sourceUnitBuilder.continuations(sourceUnits);
        return new PaperSourceIndex(PaperSourceIndex.SCHEMA_VERSION, artifact.paperId(),
                artifact.documentHash(), artifact.parserVersion(), textAnchors, result,
                sourceUnits, continuations);
    }

    private List<StatementOwner> statementOwners(List<DocumentBlock> blocks) {
        List<StatementOwner> result = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            Matcher matcher = THEOREM.matcher(block.text());
            if (matcher.find()) {
                result.add(new StatementOwner(
                        matcher.group(1).toUpperCase(Locale.ROOT), matcher.group(2), block));
            }
        }
        return List.copyOf(result);
    }

    /** Finds a structural owner before flattening independent page flows into one document order. */
    private StatementOwner nearestOwner(List<DocumentBlock> blocks,
                                        List<StatementOwner> owners,
                                        DocumentBlock equation) {
        return owners.stream()
                .filter(owner -> precedes(owner.block(), equation))
                .filter(owner -> sameFlow(owner.block(), equation))
                .filter(owner -> !sectionBoundaryBetween(blocks, owner.block(), equation))
                .max(Comparator.comparingInt((StatementOwner owner) -> owner.block().page())
                        .thenComparingInt(owner -> owner.block().readingOrder())
                        .thenComparingDouble(owner -> owner.block().bbox().y()))
                .orElse(null);
    }

    private boolean precedes(DocumentBlock owner, DocumentBlock equation) {
        if (owner.page() > equation.page() || equation.page() - owner.page() > 1) return false;
        if (owner.page() < equation.page()) return true;
        return owner.readingOrder() <= equation.readingOrder();
    }

    private boolean sameFlow(DocumentBlock owner, DocumentBlock equation) {
        // Columns are a page-local ownership guard, not separate document contexts. At a page
        // boundary the parser's reading order already connects the previous page to the next.
        if (owner.page() != equation.page()) return true;
        return owner.bbox().width() >= .72 || equation.bbox().width() >= .72
                || sameColumn(owner.bbox(), equation.bbox());
    }

    private boolean sectionBoundaryBetween(List<DocumentBlock> blocks,
                                           DocumentBlock owner,
                                           DocumentBlock equation) {
        return blocks.stream()
                .filter(block -> block.role() == DocumentBlockRole.HEADING)
                .filter(block -> !block.id().equals(owner.id()))
                .filter(block -> sameFlow(block, equation))
                .anyMatch(block -> positionBetween(owner, block, equation));
    }

    private boolean proofBetween(List<DocumentBlock> blocks,
                                 DocumentBlock owner,
                                 DocumentBlock equation) {
        return blocks.stream()
                .filter(block -> sameFlow(block, equation))
                .filter(block -> positionBetween(owner, block, equation)
                        || block.id().equals(owner.id()) || block.id().equals(equation.id()))
                .anyMatch(block -> PROOF.matcher(block.text()).find());
    }

    private boolean positionBetween(DocumentBlock start,
                                    DocumentBlock candidate,
                                    DocumentBlock end) {
        boolean afterStart = candidate.page() > start.page()
                || candidate.page() == start.page()
                && candidate.readingOrder() > start.readingOrder();
        boolean beforeEnd = candidate.page() < end.page()
                || candidate.page() == end.page()
                && candidate.readingOrder() < end.readingOrder();
        return afterStart && beforeEnd;
    }

    private boolean isDefinition(List<DocumentBlock> blocks, DocumentBlock block, int labelOffset) {
        String text = block.text().replaceAll("\\s+", " ").trim();
        if (MENTION_AT_END.matcher(text).matches()
                || FORMULA_REFERENCE.matcher(text.substring(0, Math.min(labelOffset + 16, text.length())))
                .find()) return false;
        if (text.matches("^\\(\\d{1,4}[a-z]?\\)$")) {
            return blocks.stream()
                    .filter(candidate -> candidate.page() == block.page())
                    .filter(candidate -> candidate.readingOrder() < block.readingOrder())
                    .filter(candidate -> block.readingOrder() - candidate.readingOrder() <= 12)
                    .filter(this::formulaComponent)
                    .filter(candidate -> sameColumn(block.bbox(), candidate.bbox()))
                    .anyMatch(candidate -> verticalGap(block.bbox(), candidate.bbox()) <= .075);
        }
        // A body block can contain an equals sign and a parenthesized reference
        // without being a displayed equation.  Only short, math-dense body
        // blocks are eligible for physical equation definitions; narrative
        // sentences remain mentions even when they contain operators.
        if (block.role() != DocumentBlockRole.FORMULA
                && block.contentMode() == DocumentBlockContentMode.TEXT
                && !formulaComponent(block)) return false;
        String before = text.substring(0, Math.min(labelOffset, text.length()));
        boolean operator = before.matches("(?s).*[=≈≃≤≥<>∑∏√+−].*")
                || before.toLowerCase(Locale.ROOT).matches("(?s).*\\b(max|min|argmax|argmin)\\b.*");
        return operator || block.role() == DocumentBlockRole.FORMULA
                && text.split("\\s+").length <= 18;
    }

    private SourceAnchor formulaAnchor(PaperLayoutArtifact artifact,
                                       List<DocumentBlock> blocks,
                                       DocumentBlock label,
                                       String number,
                                       List<FormulaContextBuilder.FormulaContext> formulaContexts,
                                       List<FormulaLabel> definitionLabels) {
        NormalizedBoundingBox labelBox = formulaLabelBox(label);
        FormulaContextBuilder.FormulaContext context = formulaContextBuilder
                .find(formulaContexts, label, number).orElse(null);
        // A singleton context only proves that the numbered baseline was found.
        // PDFBox often emits a fraction's numerator/denominator as separate
        // blocks, and an overlapping lead-in sentence can prevent the context
        // builder from joining them. Fall through to the local component scan
        // so the physical region still covers the whole displayed equation.
        if (context != null && context.blocks().size() > 1
                && context.blocks().stream().allMatch(block -> belongsToFormula(
                number, label, block, definitionLabels))) {
            NormalizedBoundingBox bbox = padded(context.bbox(), .006);
            return new SourceAnchor(sourceId(artifact, "equation:" + number), label.page(),
                    SourceAnchor.Kind.FORMULA_REGION, bbox, List.of(bbox), context.text(),
                    label.id(), Math.min(label.confidence(), context.confidence()));
        }
        List<DocumentBlock> components = new ArrayList<>();
        components.add(label);
        blocks.stream()
                .filter(block -> block.page() == label.page())
                .filter(block -> !block.id().equals(label.id()))
                .filter(this::formulaComponent)
                .filter(block -> Math.abs(block.readingOrder() - label.readingOrder()) <= 10)
                .filter(block -> sameColumn(labelBox, block.bbox()))
                .filter(block -> verticalGap(labelBox, block.bbox()) <= 0.060)
                // A nearby mathematical block can belong to the next/previous
                // displayed equation when PDFBox has split its rows.  Resolve
                // ownership against all numbered definitions before adding it;
                // one physical block must never be published in two equations.
                .filter(block -> belongsToFormula(number, label, block, definitionLabels))
                .forEach(components::add);
        components.sort(Comparator.comparingInt(DocumentBlock::readingOrder));
        List<NormalizedBoundingBox> componentBoxes = components.stream()
                .map(block -> block.id().equals(label.id()) ? labelBox : block.bbox())
                .toList();
        NormalizedBoundingBox union = union(componentBoxes);
        boolean reliableRegion = components.size() > 1 || label.role() == DocumentBlockRole.FORMULA;
        // A very tall union usually means adjacent equations were accidentally joined. In that
        // case the printed equation number is the honest, stable fallback.
        if (union.height() > .22 || components.size() > 8) reliableRegion = false;
        NormalizedBoundingBox bbox = reliableRegion ? padded(union, .006) : padded(labelBox, .004);
        String sourceText = components.stream().map(DocumentBlock::text)
                .map(String::trim).filter(value -> !value.isBlank())
                .distinct().reduce((first, second) -> first + " " + second).orElse("");
        return new SourceAnchor(sourceId(artifact, "equation:" + number), label.page(),
                SourceAnchor.Kind.FORMULA_REGION, bbox, List.of(bbox), sourceText,
                label.id(), reliableRegion ? label.confidence() : Math.min(label.confidence(), .55));
    }

    /**
     * Collects only physical blocks that carry a numbered display definition.
     * Equation references in prose are deliberately excluded by isDefinition.
     */
    private List<FormulaLabel> definitionLabels(List<DocumentBlock> blocks) {
        List<FormulaLabel> result = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            Matcher matcher = EQUATION.matcher(block.text());
            while (matcher.find()) {
                if (isDefinition(blocks, block, matcher.start())) {
                    result.add(new FormulaLabel(matcher.group(1), block));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Assign an unnumbered math fragment to its nearest numbered display on the
     * same page/column.  If two different equations are equally close, leave the
     * fragment out rather than risking cross-equation geometry.
     */
    private boolean belongsToFormula(String number,
                                     DocumentBlock label,
                                     DocumentBlock candidate,
                                     List<FormulaLabel> definitionLabels) {
        if (candidate == null || label == null || candidate.bbox() == null) return false;
        if (candidate.id().equals(label.id())) return true;
        List<String> candidateNumbers = equationLabels(candidate);
        if (!candidateNumbers.isEmpty()) return candidateNumbers.contains(number);

        List<FormulaLabel> owners = definitionLabels.stream()
                .filter(owner -> owner.block().page() == candidate.page())
                .filter(owner -> compatibleFormulaLane(owner.block(), candidate))
                .filter(owner -> sameColumn(formulaLabelBox(owner.block()), candidate.bbox()))
                .sorted(Comparator.comparingDouble(owner -> formulaDistance(
                        candidate.bbox(), formulaLabelBox(owner.block()))))
                .toList();
        if (owners.isEmpty()) return false;
        FormulaLabel best = owners.get(0);
        if (owners.size() > 1) {
            double bestDistance = formulaDistance(candidate.bbox(), formulaLabelBox(best.block()));
            FormulaLabel second = owners.get(1);
            double secondDistance = formulaDistance(candidate.bbox(), formulaLabelBox(second.block()));
            if (!best.number().equalsIgnoreCase(second.number())
                    && secondDistance - bestDistance <= .012) return false;
        }
        return best.number().equalsIgnoreCase(number);
    }

    private List<String> equationLabels(DocumentBlock block) {
        List<String> result = new ArrayList<>();
        Matcher matcher = EQUATION.matcher(block.text());
        while (matcher.find()) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private boolean compatibleFormulaLane(DocumentBlock first, DocumentBlock second) {
        return first.layoutLane() == second.layoutLane()
                || first.layoutLane() == DocumentLayoutLane.FULL
                || second.layoutLane() == DocumentLayoutLane.FULL;
    }

    private double formulaDistance(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        double firstCenter = first.y() + first.height() / 2.0;
        double secondCenter = second.y() + second.height() / 2.0;
        return Math.abs(firstCenter - secondCenter);
    }

    private boolean formulaComponent(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.FORMULA) return true;
        if (block.role() != DocumentBlockRole.BODY || block.mathProfile().signalCount() == 0
                || block.mathProfile().density() < .10) return false;
        Matcher prose = Pattern.compile("[A-Za-z]{4,}").matcher(block.text());
        int words = 0;
        while (prose.find() && words < 3) words++;
        return words < 3;
    }

    private NormalizedBoundingBox formulaLabelBox(DocumentBlock label) {
        NormalizedBoundingBox box = label.bbox();
        // A same-baseline PDFBox row can contain the tail of the left column and a numbered
        // equation in the right column. When the equation label is at the row end, retain only
        // the right-column half instead of publishing a page-wide action box.
        if (box.width() >= .68 && EQUATION.matcher(label.text()).find()
                && label.text().trim().matches("(?s).*\\(\\d{1,4}[a-z]?\\)\\s*$")) {
            double left = Math.max(.505, box.x());
            return new NormalizedBoundingBox(left, box.y(),
                    Math.max(.01, box.right() - left), box.height());
        }
        return box;
    }

    private MutableEquation chooseDefinition(MutableEquation current,
                                             String number,
                                             SourceAnchor definition,
                                             EquationEntity.Relation relation,
                                             String statementKind,
                                             String theorem,
                                             List<String> context) {
        if (current == null) current = new MutableEquation(number);
        // The physical display candidate is authoritative.  Semantic ownership
        // (theorem result/proof step) is metadata only and must never move a
        // formula to a later prose mention.  If extraction yields more than one
        // display candidate, prefer the stronger geometry/confidence and then
        // the earlier physical occurrence.
        if (current.definition == null || betterPhysicalDefinition(definition, current.definition)) {
            if (current.definition != null) current.mentions.add(current.definition);
            current.definition = definition;
            current.relation = relation;
            current.statementKind = statementKind;
            current.theorem = theorem;
            current.context = context;
        } else {
            current.mentions.add(definition);
        }
        return current;
    }

    private boolean betterPhysicalDefinition(SourceAnchor candidate, SourceAnchor current) {
        if (candidate == null) return false;
        if (current == null) return true;
        if (candidate.confidence() > current.confidence() + .08) return true;
        if (current.confidence() > candidate.confidence() + .08) return false;
        if (candidate.page() != current.page()) return candidate.page() < current.page();
        if (Math.abs(candidate.bbox().y() - current.bbox().y()) > .01) {
            return candidate.bbox().y() < current.bbox().y();
        }
        return candidate.bbox().x() < current.bbox().x();
    }

    private List<String> nearbyContext(List<DocumentBlock> blocks, DocumentBlock equation) {
        return blocks.stream()
                .filter(block -> block.page() == equation.page())
                .filter(block -> sameColumn(block.bbox(), equation.bbox()))
                .filter(block -> Math.abs(block.readingOrder() - equation.readingOrder()) <= 4)
                .filter(block -> block.role() == DocumentBlockRole.BODY
                        || block.role() == DocumentBlockRole.HEADING)
                .map(DocumentBlock::id).distinct().toList();
    }

    private SourceAnchor anchor(PaperLayoutArtifact artifact, DocumentBlock block,
                                SourceAnchor.Kind kind, String text,
                                NormalizedBoundingBox bbox, double confidence) {
        return new SourceAnchor(sourceId(artifact, block.id()), block.page(), kind, bbox,
                List.of(bbox), text, block.id(), confidence);
    }

    private String sourceId(PaperLayoutArtifact artifact, String suffix) {
        String value = artifact.paperId() + "|" + artifact.documentHash() + "|"
                + artifact.parserVersion() + "|source-v" + PaperSourceIndex.SCHEMA_VERSION + "|" + suffix;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "src_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean sameColumn(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        double overlap = Math.max(0,
                Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        return overlap >= Math.min(first.width(), second.width()) * 0.20;
    }

    private double verticalGap(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first.bottom() < second.y()) return second.y() - first.bottom();
        if (second.bottom() < first.y()) return first.y() - second.bottom();
        return 0;
    }

    private NormalizedBoundingBox union(List<NormalizedBoundingBox> boxes) {
        double left = boxes.stream().mapToDouble(NormalizedBoundingBox::x).min().orElse(0);
        double top = boxes.stream().mapToDouble(NormalizedBoundingBox::y).min().orElse(0);
        double right = boxes.stream().mapToDouble(NormalizedBoundingBox::right).max().orElse(left);
        double bottom = boxes.stream().mapToDouble(NormalizedBoundingBox::bottom).max().orElse(top);
        return new NormalizedBoundingBox(left, top, right - left, bottom - top);
    }

    private NormalizedBoundingBox padded(NormalizedBoundingBox box, double padding) {
        double left = Math.max(0, box.x() - padding);
        double top = Math.max(0, box.y() - padding);
        double right = Math.min(1, box.right() + padding);
        double bottom = Math.min(1, box.bottom() + padding);
        return new NormalizedBoundingBox(left, top, right - left, bottom - top);
    }

    private static final class MutableEquation {
        private final String number;
        private SourceAnchor definition;
        private EquationEntity.Relation relation = EquationEntity.Relation.OTHER;
        private String statementKind = "";
        private String theorem = "";
        private List<String> context = List.of();
        private final List<SourceAnchor> mentions = new ArrayList<>();

        private MutableEquation(String number) { this.number = number; }

        private EquationEntity freeze() {
            return new EquationEntity("eq:" + number + ":" + definition.anchorId(), number,
                    definition, relation, statementKind, theorem, context, mentions);
        }
    }

    private record StatementOwner(String kind, String number, DocumentBlock block) { }

    private record FormulaLabel(String number, DocumentBlock block) { }
}
