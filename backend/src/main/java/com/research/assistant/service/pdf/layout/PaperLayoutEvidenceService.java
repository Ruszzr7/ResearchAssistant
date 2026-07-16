package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.pdf.formula.region.ConfirmedFormulaRegionService;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final SelectionAnchorResolver anchorResolver;
    private final ConfirmedFormulaRegionService confirmedFormulaRegions;

    public PaperLayoutEvidenceService(PaperLayoutEvidencePolicy evidencePolicy,
                                      SelectionAnchorResolver anchorResolver) {
        this(evidencePolicy, anchorResolver, null);
    }

    @Autowired
    public PaperLayoutEvidenceService(PaperLayoutEvidencePolicy evidencePolicy,
                                      SelectionAnchorResolver anchorResolver,
                                      ConfirmedFormulaRegionService confirmedFormulaRegions) {
        this.evidencePolicy = evidencePolicy;
        this.anchorResolver = anchorResolver;
        this.confirmedFormulaRegions = confirmedFormulaRegions;
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
        if (confirmedFormulaRegions != null) {
            java.util.Optional<LayoutEvidence> confirmed = confirmedFormulaRegions.evidence(
                    artifact, resolvedAnchor);
            if (confirmed.isPresent()) {
                return new LocalEvidenceResult(resolvedAnchor, List.of(confirmed.get()), false);
            }
        }
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

        List<DocumentBlock> orderedSelected = selected.stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
        Set<String> selectedIds = orderedSelected.stream().map(DocumentBlock::id)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        String effectiveQuery = query == null || query.isBlank() ? resolvedAnchor.anchorText() : query;
        List<ScoredBlock> candidates = new ArrayList<>();
        for (DocumentBlock block : orderedSelected) {
            candidates.add(new ScoredBlock(block, 1, true));
        }

        List<DocumentBlock> allowed = evidencePolicy.selectAllowed(artifact).stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder)).toList();
        int firstSelectedOrder = orderedSelected.get(0).readingOrder();
        int lastSelectedOrder = orderedSelected.get(orderedSelected.size() - 1).readingOrder();
        DocumentBlock before = allowed.stream()
                .filter(block -> !selectedIds.contains(block.id()))
                .filter(block -> block.readingOrder() < firstSelectedOrder)
                .max(Comparator.comparingInt(DocumentBlock::readingOrder)).orElse(null);
        DocumentBlock after = allowed.stream()
                .filter(block -> !selectedIds.contains(block.id()))
                .filter(block -> block.readingOrder() > lastSelectedOrder)
                .min(Comparator.comparingInt(DocumentBlock::readingOrder)).orElse(null);
        addNeighbour(candidates, before, orderedSelected, effectiveQuery, firstSelectedOrder);
        addNeighbour(candidates, after, orderedSelected, effectiveQuery, lastSelectedOrder);

        List<ScoredBlock> chosen = candidates.stream()
                .sorted(Comparator.comparing((ScoredBlock item) -> !item.selected())
                        .thenComparing(Comparator.comparingDouble(ScoredBlock::score).reversed())
                        .thenComparingInt(item -> item.block().readingOrder()))
                .limit(safeMax)
                .sorted(Comparator.comparingInt(item -> item.block().readingOrder()))
                .toList();
        List<LayoutEvidence> evidence = chosen.stream()
                .map(item -> toEvidence(artifact, item.block(), item.score(), item.selected()))
                .toList();
        return new LocalEvidenceResult(
                resolvedAnchor, evidence, resolvedAnchor.kind() == SelectionAnchorKind.REGION);
    }

    private void addNeighbour(List<ScoredBlock> candidates,
                              DocumentBlock block,
                              List<DocumentBlock> selected,
                              String query,
                              int boundaryOrder) {
        if (block == null) return;
        int distance = Math.abs(block.readingOrder() - boundaryOrder);
        double lexical = LayoutTextSimilarity.queryCoverage(query, block.text());
        double sectionBonus = sameSection(block, selected) ? 0.08 : 0;
        double score = Math.min(0.90, Math.max(0.20, 0.70 - 0.08 * distance)
                + 0.12 * lexical + sectionBonus);
        candidates.add(new ScoredBlock(block, score, false));
    }

    /** Shared stable evidence projection used by local and whole-paper workbench retrieval. */
    public LayoutEvidence toEvidence(PaperLayoutArtifact artifact, DocumentBlock block,
                                     double score, boolean selected) {
        return new LayoutEvidence(
                evidenceId(artifact, block),
                artifact.paperId(),
                block.id(),
                block.page(),
                block.bbox(),
                block.role(),
                block.readingOrder(),
                block.sectionPath(),
                evidenceText(block),
                score,
                selected,
                block.confidence(),
                artifact.documentHash(),
                artifact.parserVersion(),
                block.contentMode(),
                structuredContent(block)
        );
    }

    private String evidenceText(DocumentBlock block) {
        if (block.contentMode() != DocumentBlockContentMode.REGION) return block.text();
        return switch (block.role()) {
            case FORMULA -> "[公式区域：未获得可信 LaTeX，仅可按页面区域定位和核对]";
            case TABLE -> "[表格区域：未获得可信单元格结构，仅可按页面区域定位和核对]";
            default -> "[视觉区域：没有可安全引用的精确文本]";
        };
    }

    private String structuredContent(DocumentBlock block) {
        if (block.contentMode() != DocumentBlockContentMode.STRUCTURED) return "";
        if (block.role() == DocumentBlockRole.FORMULA) return block.latex() == null ? "" : block.latex();
        if (block.role() == DocumentBlockRole.TABLE) return block.tableText() == null ? "" : block.tableText();
        return "";
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
