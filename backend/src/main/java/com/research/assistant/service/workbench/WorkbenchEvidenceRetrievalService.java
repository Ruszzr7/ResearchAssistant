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

    private static final Pattern QUERY_RUN = Pattern.compile(
            "[\\p{IsHan}]+|[\\p{IsLatin}\\p{N}_-]+|[\\p{IsGreek}\\p{N}]+");
    private static final Pattern EQUATION_LABEL = Pattern.compile("\\((\\d{1,4})\\)");
    private static final Set<String> QUERY_INTENT_TERMS = Set.of(
            "当前选区", "历史追问", "论文", "问题", "回答", "请问", "请", "为我",
            "帮我", "告诉我", "找出", "找到", "在哪", "哪里", "位置", "公式",
            "定义", "推导", "方程", "the", "and", "this", "that", "what",
            "where", "find", "locate", "show", "paper", "formula", "equation",
            "defined", "definition");

    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final PaperLayoutEvidenceService evidenceProjection;
    private final WorkbenchRetrievalPlanner retrievalPlanner;

    public WorkbenchEvidenceRetrievalService(PaperLayoutEvidencePolicy evidencePolicy,
                                             PaperLayoutEvidenceService evidenceProjection) {
        this(evidencePolicy, evidenceProjection, new WorkbenchRetrievalPlanner());
    }

    @Autowired
    public WorkbenchEvidenceRetrievalService(PaperLayoutEvidencePolicy evidencePolicy,
                                             PaperLayoutEvidenceService evidenceProjection,
                                             WorkbenchRetrievalPlanner retrievalPlanner) {
        this.evidencePolicy = evidencePolicy;
        this.evidenceProjection = evidenceProjection;
        this.retrievalPlanner = retrievalPlanner;
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

    private double significantTermCoverage(String query, String candidate) {
        if (query == null || query.isBlank() || candidate == null || candidate.isBlank()) return 0;
        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
        List<String> terms = significantTerms(query);
        if (terms.isEmpty()) return 0;
        long matched = terms.stream().filter(normalizedCandidate::contains).count();
        return (double) matched / terms.size();
    }

    private List<String> significantTerms(String query) {
        String normalized = java.text.Normalizer.normalize(query,
                java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String intent : QUERY_INTENT_TERMS) {
            normalized = normalized.replace(intent, " ");
        }
        List<String> latinOrNumeric = new ArrayList<>();
        List<String> other = new ArrayList<>();
        Matcher matcher = QUERY_RUN.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group().trim();
            if (term.length() < 2 || QUERY_INTENT_TERMS.contains(term)) continue;
            if (term.matches("[\\p{IsLatin}\\p{N}_-]+")) latinOrNumeric.add(term);
            else other.add(term);
        }
        List<String> selected = latinOrNumeric.isEmpty() ? other : latinOrNumeric;
        return selected.stream().distinct().toList();
    }

    private boolean isBroadQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return List.of("总结", "概述", "全文", "创新", "贡献", "主要方法", "主要结论",
                        "summary", "overview", "contribution", "whole paper")
                .stream().anyMatch(normalized::contains);
    }

    private boolean isReferentialQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return List.of("这个", "这一", "上述", "前面", "继续", "它", "该方法", "该公式",
                        "this", "that", "it ", "above", "continue", "former", "latter")
                .stream().anyMatch(normalized::contains);
    }

    private boolean isLocationOrFormulaQuery(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return List.of("在哪", "哪里", "位置", "公式", "定义", "推导", "方程",
                        "equation", "formula", "where", "locate", "defined")
                .stream().anyMatch(normalized::contains);
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
        String equationLabel = equationLabel(label.text());
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

    private record Candidate(DocumentBlock block,
                             double score,
                             boolean relevant,
                             Map<String, Double> routes) {
        private Candidate {
            routes = routes == null ? Map.of() : Map.copyOf(routes);
        }
    }
}
