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
            "(?i)\\b(?:theorem|lemma|proposition|corollary)\\s+(\\d+[a-z]?)\\b");
    private static final Pattern PROOF = Pattern.compile("(?i)\\bproof\\b");
    private static final Pattern MENTION_AT_END = Pattern.compile(
            "(?i).*(?:in|from|using|by|see|shown\\s+in|calculated\\s+by|given\\s+in|"
                    + "equation|eq\\.)\\s*\\(\\d{1,4}[a-z]?\\)\\s*[.,;:]?\\s*$");

    public PaperSourceIndex build(PaperLayoutArtifact artifact) {
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
        List<SourceAnchor> textAnchors = ordered.stream()
                .filter(block -> block.contentMode() == DocumentBlockContentMode.TEXT)
                .map(block -> anchor(artifact, block, SourceAnchor.Kind.TEXT_RANGE,
                        block.text(), block.bbox(), block.confidence()))
                .toList();

        Map<String, MutableEquation> equations = new LinkedHashMap<>();
        String theorem = "";
        boolean inProof = false;
        int theoremOrder = -1;
        for (DocumentBlock block : ordered) {
            Matcher theoremMatcher = THEOREM.matcher(block.text());
            if (theoremMatcher.find()) {
                theorem = theoremMatcher.group(1);
                theoremOrder = block.readingOrder();
                inProof = PROOF.matcher(block.text()).find();
            } else if (!theorem.isBlank() && PROOF.matcher(block.text()).find()) {
                inProof = true;
            } else if (!theorem.isBlank() && block.role() == DocumentBlockRole.HEADING
                    && block.readingOrder() > theoremOrder) {
                theorem = "";
                inProof = false;
            }

            Matcher matcher = EQUATION.matcher(block.text());
            while (matcher.find()) {
                String number = matcher.group(1);
                if (isDefinition(block, matcher.start())) {
                    SourceAnchor definition = formulaAnchor(artifact, ordered, block, number);
                    EquationEntity.Relation relation = theorem.isBlank()
                            ? EquationEntity.Relation.OTHER
                            : inProof ? EquationEntity.Relation.PROOF_STEP
                            : EquationEntity.Relation.THEOREM_RESULT;
                    String relatedTheorem = theorem;
                    equations.compute(number, (ignored, current) -> chooseDefinition(
                            current, number, definition, relation, relatedTheorem,
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
        return new PaperSourceIndex(PaperSourceIndex.SCHEMA_VERSION, artifact.paperId(),
                artifact.documentHash(), artifact.parserVersion(), textAnchors, result);
    }

    private boolean isDefinition(DocumentBlock block, int labelOffset) {
        String text = block.text().replaceAll("\\s+", " ").trim();
        if (MENTION_AT_END.matcher(text).matches()) return false;
        String before = text.substring(0, Math.min(labelOffset, text.length()));
        boolean operator = before.matches("(?s).*[=≈≃≤≥<>∑∏√].*")
                || before.toLowerCase(Locale.ROOT).matches("(?s).*\\b(max|min|argmax|argmin)\\b.*");
        return operator || block.role() == DocumentBlockRole.FORMULA
                && text.split("\\s+").length <= 18;
    }

    private SourceAnchor formulaAnchor(PaperLayoutArtifact artifact,
                                       List<DocumentBlock> blocks,
                                       DocumentBlock label,
                                       String number) {
        List<NormalizedBoundingBox> boxes = new ArrayList<>();
        boxes.add(label.bbox());
        blocks.stream()
                .filter(block -> block.page() == label.page())
                .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                .filter(block -> !block.id().equals(label.id()))
                .filter(block -> Math.abs(block.readingOrder() - label.readingOrder()) <= 7)
                .filter(block -> sameColumn(label.bbox(), block.bbox()))
                .filter(block -> verticalGap(label.bbox(), block.bbox()) <= 0.035)
                .map(DocumentBlock::bbox).forEach(boxes::add);
        NormalizedBoundingBox bbox = union(boxes);
        return new SourceAnchor(sourceId(artifact, "equation:" + number), label.page(),
                SourceAnchor.Kind.FORMULA_REGION, bbox, boxes, "", label.id(), label.confidence());
    }

    private MutableEquation chooseDefinition(MutableEquation current,
                                             String number,
                                             SourceAnchor definition,
                                             EquationEntity.Relation relation,
                                             String theorem,
                                             List<String> context) {
        if (current == null) current = new MutableEquation(number);
        if (current.definition == null
                || relation == EquationEntity.Relation.THEOREM_RESULT
                && current.relation != EquationEntity.Relation.THEOREM_RESULT) {
            current.definition = definition;
            current.relation = relation;
            current.theorem = theorem;
            current.context = context;
        } else {
            current.mentions.add(definition);
        }
        return current;
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

    private static final class MutableEquation {
        private final String number;
        private SourceAnchor definition;
        private EquationEntity.Relation relation = EquationEntity.Relation.OTHER;
        private String theorem = "";
        private List<String> context = List.of();
        private final List<SourceAnchor> mentions = new ArrayList<>();

        private MutableEquation(String number) { this.number = number; }

        private EquationEntity freeze() {
            return new EquationEntity("eq:" + number + ":" + definition.anchorId(), number,
                    definition, relation, theorem, context, mentions);
        }
    }
}
