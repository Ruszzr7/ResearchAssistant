package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LayoutTextSimilarity;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic whole-paper evidence sampling with section and paper diversity. */
@Service
public class WorkbenchEvidenceRetrievalService {

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
        int safeMax = Math.max(1, Math.min(80, maxEvidence));
        int safeCharacters = Math.max(2_000, Math.min(60_000, maxCharacters));
        List<DocumentBlock> allowed = evidencePolicy.selectAllowed(artifact);
        if (allowed.isEmpty()) return List.of();

        Map<String, Candidate> candidateById = new LinkedHashMap<>();
        for (DocumentBlock block : allowed) {
            candidateById.put(block.id(), new Candidate(block, score(block, query)));
        }
        List<Candidate> ranked = candidateById.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(item -> item.block().readingOrder()))
                .toList();

        Set<String> chosenIds = new LinkedHashSet<>();
        // Abstract and headings preserve the paper's global structure.
        allowed.stream()
                .filter(block -> block.role() == DocumentBlockRole.ABSTRACT)
                .limit(2)
                .forEach(block -> chosenIds.add(block.id()));
        allowed.stream()
                .filter(block -> block.role() == DocumentBlockRole.HEADING)
                .limit(Math.min(16, Math.max(4, safeMax / 3)))
                .forEach(block -> chosenIds.add(block.id()));

        // Give every represented section up to two substantive blocks before global fill.
        Map<String, List<Candidate>> bySection = new LinkedHashMap<>();
        for (Candidate candidate : ranked) {
            if (candidate.block().role() == DocumentBlockRole.HEADING) continue;
            bySection.computeIfAbsent(sectionKey(candidate.block()), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        for (List<Candidate> section : bySection.values()) {
            section.stream().limit(2).forEach(candidate -> chosenIds.add(candidate.block().id()));
        }
        ranked.forEach(candidate -> chosenIds.add(candidate.block().id()));

        List<Candidate> chosen = new ArrayList<>();
        int characters = 0;
        for (String blockId : chosenIds) {
            Candidate candidate = candidateById.get(blockId);
            if (candidate == null || chosen.size() >= safeMax) break;
            int nextCharacters = characters + candidate.block().text().length();
            if (!chosen.isEmpty() && nextCharacters > safeCharacters) continue;
            chosen.add(candidate);
            characters = nextCharacters;
        }
        chosen.sort(Comparator.comparingInt(item -> item.block().readingOrder()));
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

    private double score(DocumentBlock block, String query) {
        double roleWeight = switch (block.role()) {
            case ABSTRACT -> 0.92;
            case HEADING -> 0.84;
            case TABLE -> 0.68;
            case CAPTION -> 0.64;
            case BODY -> 0.58;
            case FORMULA -> 0.50;
            default -> 0;
        };
        double lexical = LayoutTextSimilarity.queryCoverage(query, block.text());
        return Math.min(1, roleWeight + 0.30 * lexical + 0.08 * block.confidence());
    }

    private String sectionKey(DocumentBlock block) {
        return block.sectionPath().isEmpty()
                ? "page:" + block.page()
                : String.join(" / ", block.sectionPath());
    }

    private record Candidate(DocumentBlock block, double score) {
    }
}
