package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retrieves a bounded evidence window around a version-checked selection. */
@Service
public class PaperLayoutEvidenceService {

    private static final int MAX_READING_DISTANCE = 4;

    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final SelectionAnchorResolver anchorResolver;

    public PaperLayoutEvidenceService(PaperLayoutEvidencePolicy evidencePolicy,
                                      SelectionAnchorResolver anchorResolver) {
        this.evidencePolicy = evidencePolicy;
        this.anchorResolver = anchorResolver;
    }

    public LocalEvidenceResult retrieve(PaperLayoutArtifact artifact,
                                        SelectionAnchor anchor,
                                        String query,
                                        int maxResults) {
        validateArtifact(anchor, artifact);
        SelectionAnchor resolvedAnchor = anchorResolver.resolve(
                artifact,
                anchor.page(),
                anchor.boxes(),
                anchor.anchorText(),
                anchor.kind());
        int safeMax = Math.max(1, Math.min(20, maxResults));
        Map<String, DocumentBlock> blockById = new LinkedHashMap<>();
        for (DocumentBlock block : artifact.blocks()) {
            blockById.put(block.id(), block);
        }

        List<DocumentBlock> selected = resolvedAnchor.blockIds().stream()
                .map(blockById::get)
                .filter(evidencePolicy::isAllowed)
                .toList();
        if (selected.isEmpty() && resolvedAnchor.kind() == SelectionAnchorKind.REGION) {
            selected = evidencePolicy.selectAllowed(artifact).stream()
                    .filter(block -> block.page() == resolvedAnchor.page())
                    .filter(block -> resolvedAnchor.boxes().stream().anyMatch(box ->
                            NormalizedBoxMath.selectionCoverage(box, block.bbox()) >= 0.20))
                    .toList();
        }
        if (selected.isEmpty()) {
            return new LocalEvidenceResult(
                    resolvedAnchor, List.of(), resolvedAnchor.kind() == SelectionAnchorKind.REGION);
        }

        Set<String> selectedIds = selected.stream().map(DocumentBlock::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<Integer> selectedOrders = selected.stream().map(DocumentBlock::readingOrder)
                .collect(java.util.stream.Collectors.toSet());
        String effectiveQuery = query == null || query.isBlank() ? resolvedAnchor.anchorText() : query;
        List<ScoredBlock> candidates = new ArrayList<>();
        for (DocumentBlock block : evidencePolicy.selectAllowed(artifact)) {
            int distance = selectedOrders.stream()
                    .mapToInt(order -> Math.abs(order - block.readingOrder()))
                    .min()
                    .orElse(Integer.MAX_VALUE);
            boolean directlySelected = selectedIds.contains(block.id());
            if (!directlySelected && distance > MAX_READING_DISTANCE) {
                continue;
            }
            double lexical = LayoutTextSimilarity.queryCoverage(effectiveQuery, block.text());
            double sectionBonus = sameSection(block, selected) ? 0.08 : 0;
            double score = directlySelected
                    ? 1
                    : Math.max(0.15, 0.72 - 0.11 * distance) + 0.18 * lexical + sectionBonus;
            candidates.add(new ScoredBlock(block, Math.min(1, score), directlySelected));
        }

        List<ScoredBlock> chosen = candidates.stream()
                .sorted(Comparator.comparingDouble(ScoredBlock::score).reversed()
                        .thenComparingInt(item -> item.block().readingOrder()))
                .limit(safeMax)
                .sorted(Comparator.comparingInt(item -> item.block().readingOrder()))
                .toList();
        List<LayoutEvidence> evidence = chosen.stream()
                .map(item -> toEvidence(artifact, item))
                .toList();
        return new LocalEvidenceResult(
                resolvedAnchor, evidence, resolvedAnchor.kind() == SelectionAnchorKind.REGION);
    }

    private LayoutEvidence toEvidence(PaperLayoutArtifact artifact, ScoredBlock item) {
        DocumentBlock block = item.block();
        return new LayoutEvidence(
                evidenceId(artifact, block),
                artifact.paperId(),
                block.id(),
                block.page(),
                block.bbox(),
                block.role(),
                block.readingOrder(),
                block.sectionPath(),
                block.text(),
                item.score(),
                item.selected(),
                block.confidence(),
                artifact.documentHash(),
                artifact.parserVersion()
        );
    }

    private boolean sameSection(DocumentBlock candidate, List<DocumentBlock> selected) {
        if (candidate.sectionPath().isEmpty()) {
            return false;
        }
        return selected.stream().anyMatch(block -> block.sectionPath().equals(candidate.sectionPath()));
    }

    private void validateArtifact(SelectionAnchor anchor, PaperLayoutArtifact artifact) {
        if (anchor == null || artifact == null || !artifact.paperId().equals(anchor.paperId())) {
            throw new IllegalArgumentException("selection paper mismatch");
        }
        if (anchor.boxes().isEmpty() || anchor.boxes().size() > 100
                || anchor.blockIds().size() > 100
                || anchor.anchorText().length() > 8000
                || anchor.documentHash().length() > 64
                || anchor.parserVersion().length() > 128) {
            throw new IllegalArgumentException("selection anchor exceeds limits");
        }
        if (!artifact.documentHash().equals(anchor.documentHash())
                || !artifact.parserVersion().equals(anchor.parserVersion())) {
            throw new StaleLayoutArtifactException();
        }
        if (anchor.page() < 1 || anchor.page() > artifact.pageCount()) {
            throw new IllegalArgumentException("selection page mismatch");
        }
    }

    private String evidenceId(PaperLayoutArtifact artifact, DocumentBlock block) {
        String value = artifact.paperId() + "|" + artifact.documentHash() + "|"
                + artifact.parserVersion() + "|" + block.id();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "lay_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ScoredBlock(DocumentBlock block, double score, boolean selected) {
    }
}
