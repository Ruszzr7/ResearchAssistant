package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LayoutTextSimilarity;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.PaperSourceIndexService;
import com.research.assistant.service.pdf.layout.PaperSourceIndex;
import com.research.assistant.service.pdf.layout.EquationEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Query-first whole-paper retrieval with bounded section diversity. */
@Service
public class WorkbenchEvidenceRetrievalService {

    private static final Pattern EQUATION_LABEL = Pattern.compile("\\((\\d{1,4})\\)");
    private static final Pattern EQUATION_LABEL_AT_END = Pattern.compile("\\((\\d{1,4})\\)\\s*[.,;:]?\\s*$");

    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final PaperLayoutEvidenceService evidenceProjection;
    private final WorkbenchRetrievalPlanner retrievalPlanner;
    private final PaperSourceIndexService sourceIndexService;

    public WorkbenchEvidenceRetrievalService(PaperLayoutEvidencePolicy evidencePolicy,
                                             PaperLayoutEvidenceService evidenceProjection) {
        this(evidencePolicy, evidenceProjection, new WorkbenchRetrievalPlanner(),
                new PaperSourceIndexService());
    }

    @Autowired
    public WorkbenchEvidenceRetrievalService(PaperLayoutEvidencePolicy evidencePolicy,
                                             PaperLayoutEvidenceService evidenceProjection,
                                             WorkbenchRetrievalPlanner retrievalPlanner,
                                             PaperSourceIndexService sourceIndexService) {
        this.evidencePolicy = evidencePolicy;
        this.evidenceProjection = evidenceProjection;
        this.retrievalPlanner = retrievalPlanner;
        this.sourceIndexService = sourceIndexService;
    }

    public List<LayoutEvidence> retrievePaper(PaperLayoutArtifact artifact,
                                              String query,
                                              int maxEvidence,
                                              int maxCharacters) {
        return retrievePaper(artifact, query, List.of(), maxEvidence, maxCharacters);
    }

    public List<LayoutEvidence> retrievePaper(PaperLayoutArtifact artifact,
                                              String query,
                                              List<String> preferredBlockIds,
                                              int maxEvidence,
                                              int maxCharacters) {
        int safeMax = Math.max(1, Math.min(80, maxEvidence));
        int safeCharacters = Math.max(2_000, Math.min(60_000, maxCharacters));
        List<DocumentBlock> allAllowed = evidencePolicy.selectAllowed(artifact);
        // Region-only blocks do not participate in lexical ranking. For a
        // location/formula question they are added later only when adjacent to
        // a text block that actually matches the question.
        List<DocumentBlock> allowed = allAllowed.stream()
                .filter(block -> block.contentMode() != DocumentBlockContentMode.REGION)
                .toList();
        if (allowed.isEmpty()) return List.of();

        Set<String> preferred = preferredBlockIds == null
                ? Set.of() : new LinkedHashSet<>(preferredBlockIds);
        WorkbenchRetrievalPlan plan = retrievalPlanner.plan(query);
        if (plan.formulaOverview()) {
            return retrieveFormulaOverview(
                    artifact, allAllowed, allowed, safeMax, safeCharacters);
        }
        boolean broadQuery = plan.broad();
        Map<String, Candidate> candidateById = new LinkedHashMap<>();
        for (DocumentBlock block : allowed) {
            candidateById.put(block.id(), candidate(
                    block, plan, preferred.contains(block.id())));
        }
        List<Candidate> ranked = fuseRankings(candidateById.values()).stream()
                .filter(Candidate::relevant)
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(item -> item.block().readingOrder()))
                .toList();
        if (ranked.isEmpty()) return List.of();

        Set<String> chosenIds = new LinkedHashSet<>();
        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        int perSectionLimit = broadQuery ? 2 : 3;
        for (Candidate candidate : ranked) {
            String section = sectionKey(candidate.block());
            int count = sectionCounts.getOrDefault(section, 0);
            if (count >= perSectionLimit && !preferred.contains(candidate.block().id())) continue;
            chosenIds.add(candidate.block().id());
            sectionCounts.put(section, count + 1);
            if (chosenIds.size() >= safeMax) break;
        }
        List<Candidate> chosen = new ArrayList<>();
        int characters = 0;
        double topScore = ranked.get(0).score();
        double relativeFloor = broadQuery ? 0 : Math.max(0.10, topScore * 0.32);
        int baseLimit = plan.formulaOrLocation() && safeMax > 2 ? safeMax - 2 : safeMax;
        for (String blockId : chosenIds) {
            Candidate candidate = candidateById.get(blockId);
            if (candidate == null || chosen.size() >= baseLimit) break;
            if (!preferred.contains(blockId) && candidate.score() < relativeFloor) continue;
            int nextCharacters = characters + candidate.block().text().length();
            if (!chosen.isEmpty() && nextCharacters > safeCharacters) continue;
            chosen.add(candidate);
            characters = nextCharacters;
        }
        for (Candidate context : adjacentTextEvidence(allowed, chosen, plan.neighbourRadius())) {
            if (chosen.size() >= baseLimit) break;
            int nextCharacters = characters + context.block().text().length();
            if (nextCharacters > safeCharacters) continue;
            chosen.add(context);
            characters = nextCharacters;
        }
        if (plan.formulaOrLocation()) {
            for (Candidate adjacent : adjacentRegionEvidence(allAllowed, chosen)) {
                if (chosen.size() >= safeMax) break;
                chosen.add(adjacent);
            }
        }
        if (broadQuery) {
            chosen.sort(Comparator.comparingInt(item -> item.block().readingOrder()));
        }
        return chosen.stream()
                .map(item -> evidenceProjection.toEvidence(artifact, item.block(), item.score(), false)
                        .withRetrieval(item.score(), item.routes().keySet().stream().toList()))
                .toList();
    }

    /**
     * A paper-wide formula judgement cannot be retrieved through literal query overlap: the
     * question is usually Chinese, while the equations and their prose are commonly English.
     * Use the PDF's numbered-equation structure as anchors and attach nearby explanatory text.
     */
    private List<LayoutEvidence> retrieveFormulaOverview(PaperLayoutArtifact artifact,
                                                         List<DocumentBlock> allAllowed,
                                                         List<DocumentBlock> textBlocks,
                                                         int maxEvidence,
                                                         int maxCharacters) {
        int formulaLimit = Math.max(2, Math.min(6, maxEvidence / 2));
        PaperSourceIndex sourceIndex = sourceIndexService.build(artifact);
        List<Candidate> ranked = sourceIndex.equations().stream()
                .map(entity -> sourceEquationCandidate(entity, allAllowed))
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(item -> item.block().readingOrder()))
                .toList();
        if (ranked.isEmpty()) ranked = allAllowed.stream()
                .filter(this::looksLikeNumberedEquation)
                .map(block -> formulaOverviewCandidate(allAllowed, block))
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(item -> item.block().readingOrder()))
                .toList();
        if (ranked.isEmpty()) {
            ranked = allAllowed.stream()
                    .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                    .filter(block -> block.contentMode() == DocumentBlockContentMode.STRUCTURED)
                    .filter(block -> !safe(block.latex()).isBlank() || !safe(block.text()).isBlank())
                    .map(this::structuredFormulaOverviewCandidate)
                    .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                            .thenComparingInt(item -> item.block().readingOrder()))
                    .toList();
        }
        if (ranked.isEmpty()) return List.of();

        List<Candidate> formulas = selectFormulaSections(ranked, formulaLimit);
        List<Candidate> chosen = new ArrayList<>(formulas);
        Set<String> existing = formulas.stream().map(item -> item.block().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (Candidate context : formulaContextEvidence(textBlocks, formulas)) {
            if (chosen.size() >= maxEvidence || !existing.add(context.block().id())) continue;
            chosen.add(context);
        }
        textBlocks.stream()
                .filter(block -> block.role() == DocumentBlockRole.ABSTRACT)
                .findFirst()
                .ifPresent(block -> {
                    if (chosen.size() < maxEvidence && existing.add(block.id())) {
                        chosen.add(new Candidate(block, 0.42, true,
                                Map.of("PAPER_OVERVIEW", 1.0)));
                    }
                });

        chosen.sort(Comparator.comparingInt(item -> item.block().readingOrder()));
        List<LayoutEvidence> result = new ArrayList<>();
        int characters = 0;
        for (Candidate item : chosen) {
            if (result.size() >= maxEvidence) break;
            int nextCharacters = characters + item.block().text().length();
            if (!result.isEmpty() && nextCharacters > maxCharacters) continue;
            result.add(evidenceProjection.toEvidence(
                            artifact, item.block(), item.score(), false)
                    .withRetrieval(item.score(), item.routes().keySet().stream().toList()));
            characters = nextCharacters;
        }
        return List.copyOf(result);
    }

    private Candidate sourceEquationCandidate(EquationEntity entity,
                                              List<DocumentBlock> blocks) {
        DocumentBlock sourceBlock = blocks.stream()
                .filter(block -> block.id().equals(entity.definition().blockId()))
                .findFirst().orElse(null);
        int readingOrder = sourceBlock == null ? 0 : sourceBlock.readingOrder();
        List<String> section = new ArrayList<>(sourceBlock == null
                ? List.of() : sourceBlock.sectionPath());
        section.add("Equation (" + entity.number() + ")");
        if (entity.relation() == EquationEntity.Relation.THEOREM_RESULT) {
            section.add("Theorem " + entity.theoremNumber() + " result");
        } else if (entity.relation() == EquationEntity.Relation.PROOF_STEP) {
            section.add("Theorem " + entity.theoremNumber() + " proof step");
        }
        double score = switch (entity.relation()) {
            case THEOREM_RESULT -> 0.96;
            case OTHER -> 0.68;
            case PROOF_STEP -> 0.52;
        };
        DocumentBlock block = new DocumentBlock(
                "equation-entity:" + entity.number() + ":" + entity.definition().blockId(),
                entity.definition().page(), entity.definition().bbox(), DocumentBlockRole.FORMULA,
                readingOrder, section, "[Equation (" + entity.number() + ")]", null, null,
                entity.definition().confidence(), DocumentBlockContentMode.REGION);
        return new Candidate(block, score, true,
                Map.of("SOURCE_EQUATION", 1.0,
                        entity.relation() == EquationEntity.Relation.THEOREM_RESULT
                                ? "THEOREM_RESULT" : "EQUATION_CONTEXT", 1.0));
    }

    private boolean looksLikeNumberedEquation(DocumentBlock block) {
        if (equationLabelAtEnd(block.text()) == null) return false;
        String content = safe(block.text()) + " " + safe(block.latex());
        String normalized = content.toLowerCase(Locale.ROOT);
        boolean mathematical = content.matches("(?s).*[=≈≃≤≥<>∑∏√_].*")
                || normalized.matches("(?s).*\\b(max|min|argmax|argmin)\\b.*");
        return mathematical && (block.role() == DocumentBlockRole.FORMULA
                || block.role() == DocumentBlockRole.BODY
                || block.contentMode() == DocumentBlockContentMode.STRUCTURED);
    }

    private Candidate formulaOverviewCandidate(List<DocumentBlock> allAllowed,
                                               DocumentBlock labelBlock) {
        DocumentBlock formula = equationCluster(
                allAllowed, labelBlock, equationLabelAtEnd(labelBlock.text()));
        String section = sectionKey(labelBlock).toLowerCase(Locale.ROOT);
        double sectionWeight = containsAny(section,
                "method", "model", "formulation", "optimization", "algorithm",
                "theorem", "方法", "模型", "优化", "问题") ? 0.28 : 0.12;
        if (containsAny(section, "proof", "appendix", "reference", "证明", "附录", "参考")) {
            sectionWeight -= 0.24;
        }
        String label = equationLabelAtEnd(labelBlock.text());
        long references = allAllowed.stream()
                .filter(block -> !block.id().equals(labelBlock.id()))
                .filter(block -> safe(block.text()).contains(label))
                .limit(6)
                .count();
        double referenceWeight = Math.min(0.24, references * 0.04);
        double structured = labelBlock.contentMode() == DocumentBlockContentMode.STRUCTURED ? 0.10 : 0;
        double score = Math.max(0.12, Math.min(1,
                0.30 + sectionWeight + referenceWeight + structured
                        + 0.08 * labelBlock.confidence()));
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("NUMBERED_FORMULA", 1.0);
        routes.put("FORMULA_SECTION", Math.max(0.1, sectionWeight + 0.24));
        if (references > 0) routes.put("EQUATION_REFERENCE", Math.min(1.0, references / 4.0));
        return new Candidate(formula, score, true, routes);
    }

    private Candidate structuredFormulaOverviewCandidate(DocumentBlock block) {
        String section = sectionKey(block).toLowerCase(Locale.ROOT);
        double sectionWeight = containsAny(section,
                "method", "model", "formulation", "optimization", "algorithm",
                "theorem", "方法", "模型", "优化", "问题") ? 0.28 : 0.12;
        if (containsAny(section, "proof", "appendix", "reference", "证明", "附录", "参考")) {
            sectionWeight -= 0.24;
        }
        double score = Math.max(0.16, Math.min(1,
                0.40 + sectionWeight + 0.10 * block.confidence()));
        return new Candidate(block, score, true,
                Map.of("STRUCTURED_FORMULA", 1.0, "FORMULA_SECTION",
                        Math.max(0.1, sectionWeight + 0.24)));
    }

    private List<Candidate> selectFormulaSections(List<Candidate> ranked,
                                                  int formulaLimit) {
        List<Candidate> selected = new ArrayList<>();
        Set<String> selectedIds = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        for (Candidate candidate : ranked) {
            if (selected.size() >= formulaLimit) break;
            if (!sections.add(formulaSectionKey(candidate.block()))) continue;
            selected.add(candidate);
            selectedIds.add(candidate.block().id());
        }
        Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        selected.forEach(item -> sectionCounts.merge(formulaSectionKey(item.block()), 1, Integer::sum));
        for (Candidate candidate : ranked) {
            if (selected.size() >= formulaLimit) break;
            if (selectedIds.contains(candidate.block().id())) continue;
            String section = formulaSectionKey(candidate.block());
            if (sectionCounts.getOrDefault(section, 0) >= 2) continue;
            selected.add(candidate);
            selectedIds.add(candidate.block().id());
            sectionCounts.merge(section, 1, Integer::sum);
        }
        return List.copyOf(selected);
    }

    private List<Candidate> formulaContextEvidence(List<DocumentBlock> textBlocks,
                                                   List<Candidate> formulas) {
        List<Candidate> result = new ArrayList<>();
        Set<String> existing = new LinkedHashSet<>();
        for (Candidate formula : formulas) {
            List<DocumentBlock> nearby = textBlocks.stream()
                    .filter(block -> block.role() == DocumentBlockRole.BODY
                            || block.role() == DocumentBlockRole.CAPTION)
                    .filter(block -> block.page() == formula.block().page())
                    .filter(block -> block.sectionPath().equals(formula.block().sectionPath().stream()
                            .filter(value -> !value.startsWith("Equation (")).toList()))
                    .filter(block -> sameColumn(formula.block().bbox(), block.bbox()))
                    .filter(block -> Math.abs(block.readingOrder() - formula.block().readingOrder()) <= 10)
                    .filter(block -> safe(block.text()).trim().length() >= 24)
                    .filter(block -> !looksLikeNumberedEquation(block))
                    .sorted(Comparator.comparingInt(block ->
                            Math.abs(block.readingOrder() - formula.block().readingOrder())))
                    .limit(2)
                    .toList();
            for (DocumentBlock block : nearby) {
                if (!existing.add(block.id())) continue;
                result.add(new Candidate(block, Math.max(0.20, formula.score() - 0.14), true,
                        Map.of("FORMULA_CONTEXT", 1.0)));
            }
        }
        return result;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    public List<LayoutEvidence> retrieveComparison(List<PaperLayoutArtifact> artifacts,
                                                   String query,
                                                   int maxEvidence,
                                                   int maxCharacters) {
        if (artifacts == null || artifacts.isEmpty()) return List.of();
        int paperCount = artifacts.size();
        int perPaperEvidence = Math.max(4, Math.min(16, maxEvidence / paperCount));
        int perPaperCharacters = Math.max(3_000, maxCharacters / paperCount);
        List<LayoutEvidence> result = new ArrayList<>();
        for (PaperLayoutArtifact artifact : artifacts) {
            result.addAll(retrievePaper(artifact, query, perPaperEvidence, perPaperCharacters));
        }
        return List.copyOf(result);
    }

    private Candidate candidate(DocumentBlock block,
                                WorkbenchRetrievalPlan plan,
                                boolean preferred) {
        boolean broadQuery = plan.broad();
        double roleWeight = switch (block.role()) {
            case ABSTRACT -> broadQuery ? 1.0 : 0.35;
            case HEADING -> broadQuery ? 0.80 : 0.30;
            case TABLE -> 0.62;
            case CAPTION -> 0.52;
            case BODY -> 0.58;
            case FORMULA -> 0.68;
            default -> 0;
        };
        String searchable = block.text() + " " + safe(block.latex()) + " "
                + safe(block.tableText()) + " " + String.join(" ", block.sectionPath());
        double lexical = Math.max(
                LayoutTextSimilarity.queryCoverage(plan.originalQuery(), searchable),
                plannedTermCoverage(plan, searchable));
        boolean phraseMatch = plan.phrases().stream().anyMatch(phrase ->
                searchable.toLowerCase(Locale.ROOT).contains(phrase));
        double phraseBonus = phraseMatch ? 0.12 : 0;
        boolean definitionMatch = plan.queryType() == WorkbenchRetrievalPlan.QueryType.DEFINITION
                && containsDefinitionCue(block.text(), plan.terms());
        double definitionBonus = definitionMatch ? 0.10 : 0;
        double structured = block.contentMode() == DocumentBlockContentMode.STRUCTURED ? 0.06 : 0;
        double followUp = preferred ? (plan.referentialFollowUp() ? 0.30 : 0.08) : 0;
        double score = Math.min(1, 0.72 * lexical + 0.14 * roleWeight
                + 0.06 * block.confidence() + structured + followUp + phraseBonus + definitionBonus);
        boolean relevant = lexical > 0 || preferred || broadQuery;
        Map<String, Double> routes = new LinkedHashMap<>();
        if (phraseMatch) routes.put("EXACT_PHRASE", 1.0);
        if (lexical > 0) routes.put("LEXICAL_TERM", lexical);
        double sectionCoverage = plannedTermCoverage(plan, String.join(" ", block.sectionPath()));
        if (sectionCoverage > 0) routes.put("SECTION_PATH", sectionCoverage);
        if (definitionMatch) routes.put("DEFINITION_CUE", 1.0);
        if (preferred) routes.put("CONVERSATION_HINT", plan.referentialFollowUp() ? 1.0 : 0.25);
        if (structured > 0) routes.put("STRUCTURED_CONTENT", 0.7);
        if (broadQuery && roleWeight > 0) routes.put("STRUCTURAL_OVERVIEW", roleWeight);
        return new Candidate(block, relevant ? score : 0, relevant, routes);
    }

    /** Reciprocal-rank fusion prevents one noisy scoring route from dominating all evidence. */
    private List<Candidate> fuseRankings(java.util.Collection<Candidate> candidates) {
        List<Candidate> values = candidates.stream().filter(Candidate::relevant).toList();
        if (values.isEmpty()) return List.of();
        Set<String> routeNames = values.stream().flatMap(item -> item.routes().keySet().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> fused = new LinkedHashMap<>();
        for (String route : routeNames) {
            List<Candidate> routeRanked = values.stream()
                    .filter(item -> item.routes().getOrDefault(route, 0.0) > 0)
                    .sorted(Comparator.comparingDouble(
                            (Candidate item) -> item.routes().get(route)).reversed()
                            .thenComparingInt(item -> item.block().readingOrder()))
                    .toList();
            for (int index = 0; index < routeRanked.size(); index++) {
                Candidate item = routeRanked.get(index);
                fused.merge(item.block().id(), 1.0 / (20 + index + 1), Double::sum);
            }
        }
        double maxFusion = fused.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        return values.stream().map(item -> {
            double normalizedFusion = fused.getOrDefault(item.block().id(), 0.0) / maxFusion;
            double combined = Math.min(1, 0.68 * item.score() + 0.32 * normalizedFusion);
            return new Candidate(item.block(), combined, item.relevant(), item.routes());
        }).toList();
    }

    private double plannedTermCoverage(WorkbenchRetrievalPlan plan, String candidate) {
        if (candidate == null || candidate.isBlank() || plan.terms().isEmpty()) return 0;
        String normalized = java.text.Normalizer.normalize(candidate,
                java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        double matchedWeight = 0;
        double totalWeight = 0;
        for (String term : plan.terms()) {
            double weight = term.matches("[a-z0-9_+/-]{2,}") ? 1.25 : 1.0;
            totalWeight += weight;
            if (normalized.contains(term)) matchedWeight += weight;
        }
        return totalWeight == 0 ? 0 : matchedWeight / totalWeight;
    }

    private boolean containsDefinitionCue(String text, List<String> terms) {
        String normalized = safe(text).toLowerCase(Locale.ROOT);
        if (terms.stream().noneMatch(normalized::contains)) return false;
        return List.of(" is ", " are ", "defined", "denotes", "represents", "refers to", "称为", "定义为")
                .stream().anyMatch(normalized::contains);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<Candidate> adjacentRegionEvidence(List<DocumentBlock> allAllowed,
                                                   List<Candidate> chosen) {
        if (chosen.isEmpty()) return List.of();
        List<Candidate> anchors = chosen.stream()
                .filter(Candidate::relevant)
                .limit(6)
                .toList();
        if (anchors.isEmpty()) return List.of();
        Set<String> existing = chosen.stream().map(item -> item.block().id())
                .collect(java.util.stream.Collectors.toSet());
        List<Candidate> result = new ArrayList<>();
        for (Candidate anchor : anchors) {
            DocumentBlock formula = adjacentEquationRegion(allAllowed, anchor.block());
            if (formula == null || !existing.add(formula.id())) continue;
            result.add(new Candidate(
                    formula, Math.max(0.35, anchor.score() - 0.03), true,
                    Map.of("ADJACENT_FORMULA", 1.0)));
        }
        return result;
    }

    private List<Candidate> adjacentTextEvidence(List<DocumentBlock> blocks,
                                                 List<Candidate> chosen,
                                                 int radius) {
        if (radius <= 0 || chosen.isEmpty()) return List.of();
        Set<String> existing = chosen.stream().map(item -> item.block().id())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Candidate> result = new ArrayList<>();
        for (Candidate anchor : chosen.stream().limit(4).toList()) {
            blocks.stream()
                    .filter(block -> !existing.contains(block.id()))
                    .filter(block -> block.role() == DocumentBlockRole.BODY
                            || block.role() == DocumentBlockRole.CAPTION
                            || block.role() == DocumentBlockRole.FORMULA
                            || block.role() == DocumentBlockRole.TABLE)
                    .filter(block -> block.page() == anchor.block().page())
                    .filter(block -> block.sectionPath().equals(anchor.block().sectionPath()))
                    .filter(block -> sameColumn(anchor.block().bbox(), block.bbox()))
                    .filter(block -> Math.abs(block.readingOrder() - anchor.block().readingOrder()) <= radius)
                    .sorted(Comparator.comparingInt(block ->
                            Math.abs(block.readingOrder() - anchor.block().readingOrder())))
                    .limit(radius)
                    .forEach(block -> {
                        if (!existing.add(block.id())) return;
                        result.add(new Candidate(block, Math.max(0.12, anchor.score() - 0.16), true,
                                Map.of("ADJACENT_CONTEXT", 1.0)));
                    });
        }
        return result;
    }

    private DocumentBlock adjacentEquationRegion(List<DocumentBlock> blocks,
                                                 DocumentBlock anchor) {
        DocumentBlock label = blocks.stream()
                .filter(block -> block.page() == anchor.page())
                .filter(block -> block.readingOrder() >= anchor.readingOrder())
                .filter(block -> sameColumn(anchor.bbox(), block.bbox()))
                .filter(block -> equationLabel(block.text()) != null)
                .filter(block -> verticalDistanceAfter(anchor.bbox(), block.bbox()) <= 0.25)
                .min(Comparator
                        .comparingDouble((DocumentBlock block) ->
                                verticalDistanceAfter(anchor.bbox(), block.bbox()))
                        .thenComparingInt(DocumentBlock::readingOrder))
                .orElse(null);
        if (label != null) return equationCluster(blocks, label);

        return blocks.stream()
                .filter(block -> block.page() == anchor.page())
                .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                .filter(block -> block.contentMode() == DocumentBlockContentMode.REGION)
                .filter(block -> block.readingOrder() >= anchor.readingOrder())
                .filter(block -> sameColumn(anchor.bbox(), block.bbox()))
                .filter(block -> verticalDistanceAfter(anchor.bbox(), block.bbox()) <= 0.25)
                .min(Comparator
                        .comparingDouble((DocumentBlock block) ->
                                verticalDistanceAfter(anchor.bbox(), block.bbox()))
                        .thenComparingInt(DocumentBlock::readingOrder))
                .orElse(null);
    }

    private DocumentBlock equationCluster(List<DocumentBlock> blocks,
                                          DocumentBlock label) {
        return equationCluster(blocks, label, equationLabel(label.text()));
    }

    private DocumentBlock equationCluster(List<DocumentBlock> blocks,
                                          DocumentBlock label,
                                          String equationLabel) {
        List<DocumentBlock> members = new ArrayList<>();
        blocks.stream()
                .filter(block -> block.page() == label.page())
                .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                .filter(block -> block.contentMode() == DocumentBlockContentMode.REGION)
                .filter(block -> sameColumn(label.bbox(), block.bbox()))
                .filter(block -> Math.abs(block.readingOrder() - label.readingOrder()) <= 8)
                .filter(block -> block.bbox().bottom() >= label.bbox().y() - 0.02)
                .filter(block -> block.bbox().y() <= label.bbox().bottom() + 0.06)
                .filter(block -> verticalGap(label.bbox(), block.bbox()) <= 0.04)
                .forEach(members::add);
        // The label block often contains the readable baseline and equation number while REGION
        // members contain only tall glyph fragments. Excluding it creates a visibly narrow target.
        List<NormalizedBoundingBox> clusterBoxes = new ArrayList<>();
        clusterBoxes.add(label.bbox());
        clusterBoxes.addAll(members.stream().map(DocumentBlock::bbox).toList());
        NormalizedBoundingBox bbox = union(clusterBoxes);
        List<String> section = new ArrayList<>(label.sectionPath());
        section.add("Equation " + equationLabel);
        return new DocumentBlock(
                "equation-region:" + label.id(), label.page(), bbox,
                DocumentBlockRole.FORMULA, label.readingOrder(), section, "",
                null, null, label.confidence(), DocumentBlockContentMode.REGION);
    }

    private String equationLabel(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = EQUATION_LABEL.matcher(text);
        return matcher.find() ? "(" + matcher.group(1) + ")" : null;
    }

    private String equationLabelAtEnd(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = EQUATION_LABEL_AT_END.matcher(text);
        return matcher.find() ? "(" + matcher.group(1) + ")" : null;
    }

    private boolean sameColumn(NormalizedBoundingBox first,
                               NormalizedBoundingBox second) {
        double overlap = Math.max(0,
                Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        return overlap >= Math.min(first.width(), second.width()) * 0.20;
    }

    private double verticalDistanceAfter(NormalizedBoundingBox anchor,
                                         NormalizedBoundingBox candidate) {
        if (candidate.bottom() < anchor.y() - 0.02) return Double.POSITIVE_INFINITY;
        return Math.max(0, candidate.y() - anchor.bottom());
    }

    private double verticalGap(NormalizedBoundingBox first,
                               NormalizedBoundingBox second) {
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

    private String sectionKey(DocumentBlock block) {
        return block.sectionPath().isEmpty()
                ? "page:" + block.page()
                : String.join(" / ", block.sectionPath());
    }

    private String formulaSectionKey(DocumentBlock block) {
        List<String> section = block.sectionPath().stream()
                .filter(value -> !value.startsWith("Equation ("))
                .filter(value -> !value.matches("(?i)(?:theorem|lemma|proposition|corollary) .* (?:result|proof step)"))
                .toList();
        return section.isEmpty() ? "page:" + block.page() : String.join(" / ", section);
    }

    private record Candidate(DocumentBlock block,
                             double score,
                             boolean relevant,
                             Map<String, Double> routes) {
        private Candidate {
            routes = routes == null ? Map.of() : Map.copyOf(routes);
        }
    }
}
