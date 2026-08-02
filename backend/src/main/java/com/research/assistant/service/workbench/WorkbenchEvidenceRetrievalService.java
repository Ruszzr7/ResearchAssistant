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

    public WorkbenchEvidenceRetrievalService(PaperLayoutEvidencePolicy evidencePolicy,
                                             PaperLayoutEvidenceService evidenceProjection) {
        this.evidencePolicy = evidencePolicy;
        this.evidenceProjection = evidenceProjection;
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
        boolean broadQuery = isBroadQuery(query);
        Map<String, Candidate> candidateById = new LinkedHashMap<>();
        for (DocumentBlock block : allowed) {
            candidateById.put(block.id(), candidate(
                    block, query, preferred.contains(block.id()), broadQuery));
        }
        List<Candidate> ranked = candidateById.values().stream()
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
        int baseLimit = isLocationOrFormulaQuery(query) && safeMax > 2 ? safeMax - 2 : safeMax;
        for (String blockId : chosenIds) {
            Candidate candidate = candidateById.get(blockId);
            if (candidate == null || chosen.size() >= baseLimit) break;
            int nextCharacters = characters + candidate.block().text().length();
            if (!chosen.isEmpty() && nextCharacters > safeCharacters) continue;
            chosen.add(candidate);
            characters = nextCharacters;
        }
        if (isLocationOrFormulaQuery(query)) {
            for (Candidate adjacent : adjacentRegionEvidence(allAllowed, chosen)) {
                if (chosen.size() >= safeMax) break;
                chosen.add(adjacent);
            }
        }
        if (broadQuery) {
            chosen.sort(Comparator.comparingInt(item -> item.block().readingOrder()));
        }
        return chosen.stream()
                .map(item -> evidenceProjection.toEvidence(artifact, item.block(), item.score(), false))
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
                                String query,
                                boolean preferred,
                                boolean broadQuery) {
        double roleWeight = switch (block.role()) {
            case ABSTRACT -> broadQuery ? 1.0 : 0.35;
            case HEADING -> broadQuery ? 0.80 : 0.30;
            case TABLE -> 0.62;
            case CAPTION -> 0.52;
            case BODY -> 0.58;
            case FORMULA -> 0.68;
            default -> 0;
        };
        String searchable = block.text() + " " + String.join(" ", block.sectionPath());
        double lexical = Math.max(
                LayoutTextSimilarity.queryCoverage(query, searchable),
                significantTermCoverage(query, searchable));
        double structured = block.contentMode() == DocumentBlockContentMode.STRUCTURED ? 0.06 : 0;
        double followUp = preferred ? (isReferentialQuery(query) ? 0.30 : 0.08) : 0;
        double score = Math.min(1, 0.72 * lexical + 0.14 * roleWeight
                + 0.06 * block.confidence() + structured + followUp);
        boolean relevant = lexical > 0 || preferred || broadQuery;
        return new Candidate(block, relevant ? score : 0, relevant);
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
                    formula, Math.max(0.35, anchor.score() - 0.03), true));
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
        members.add(label);
        blocks.stream()
                .filter(block -> block.page() == label.page())
                .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                .filter(block -> block.contentMode() == DocumentBlockContentMode.REGION)
                .filter(block -> sameColumn(label.bbox(), block.bbox()))
                .filter(block -> Math.abs(block.readingOrder() - label.readingOrder()) <= 8)
                .filter(block -> block.bbox().bottom() >= label.bbox().y() - 0.02)
                .filter(block -> verticalGap(label.bbox(), block.bbox()) <= 0.04)
                .forEach(members::add);
        NormalizedBoundingBox bbox = union(members.stream().map(DocumentBlock::bbox).toList());
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

    private record Candidate(DocumentBlock block, double score, boolean relevant) {
    }
}
