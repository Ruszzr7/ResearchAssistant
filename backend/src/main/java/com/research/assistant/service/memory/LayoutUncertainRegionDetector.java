package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.DocumentLayoutLane;
import com.research.assistant.service.pdf.layout.FormulaContextBuilder;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperSourceIndexService;
import com.research.assistant.service.pdf.layout.PaperSourceUnit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Locates high-signal parser uncertainty; it does not attempt semantic repair. */
@Component
public class LayoutUncertainRegionDetector {

    private static final double LOW_CONFIDENCE = 0.62;
    /** Small vertical margin so the recovery crop includes equation baselines and symbols. */
    private static final double FORMULA_VERTICAL_PADDING = 0.012;
    private final PaperSourceIndexService sourceIndexService;
    private final FormulaContextBuilder formulaContextBuilder;

    public LayoutUncertainRegionDetector() {
        this(new PaperSourceIndexService(), new FormulaContextBuilder());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LayoutUncertainRegionDetector(PaperSourceIndexService sourceIndexService) {
        this(sourceIndexService, new FormulaContextBuilder());
    }

    LayoutUncertainRegionDetector(PaperSourceIndexService sourceIndexService,
                                  FormulaContextBuilder formulaContextBuilder) {
        this.sourceIndexService = sourceIndexService;
        this.formulaContextBuilder = formulaContextBuilder;
    }

    public List<LayoutUncertainRegion> detect(PaperLayoutArtifact artifact) {
        if (artifact == null || artifact.blocks().isEmpty()) return List.of();
        List<DocumentBlock> blocks = artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        detectImpossibleLaneTransitions(blocks, candidates);
        LinkedHashSet<String> groupedVisualBlocks = new LinkedHashSet<>();
        Map<String, FormulaContextBuilder.FormulaContext> formulaContexts = formulaContextBuilder.build(artifact)
                .stream()
                .flatMap(context -> context.blocks().stream()
                        .map(block -> Map.entry(block.id(), context)))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, ignored) -> first, LinkedHashMap::new));
        for (FormulaContextBuilder.FormulaContext context : new LinkedHashSet<>(formulaContexts.values())) {
            if (context.blocks().stream().noneMatch(this::requiresVisualRecovery)) continue;
            candidates.add(new Candidate("VISUAL_CONTENT", context.blocks(),
                    visualAreaBoxes(context.blocks(), context.blocks(), blocks)));
            context.blocks().forEach(block -> groupedVisualBlocks.add(block.id()));
        }
        for (PaperSourceUnit unit : sourceIndexService.build(artifact).sourceUnits()) {
            List<DocumentBlock> recoveryTargets = unit.blocks().stream()
                    .filter(block -> block.role() != DocumentBlockRole.FORMULA)
                    .filter(this::requiresVisualRecovery)
                    .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                    .toList();
            for (DocumentBlock target : recoveryTargets) {
                if (groupedVisualBlocks.contains(target.id())) continue;
                List<DocumentBlock> group = List.of(target);
                candidates.add(new Candidate("VISUAL_CONTENT", group,
                        visualAreaBoxes(group, unit.blocks(), blocks)));
                group.forEach(groupedBlock -> groupedVisualBlocks.add(groupedBlock.id()));
            }
        }
        for (DocumentBlock block : blocks) {
            if (requiresVisualRecovery(block) && !groupedVisualBlocks.contains(block.id())) {
                FormulaContextBuilder.FormulaContext context = formulaContexts.get(block.id());
                List<DocumentBlock> group = context == null ? List.of(block) : context.blocks();
                candidates.add(new Candidate("VISUAL_CONTENT", group,
                        visualAreaBoxes(group, blocks)));
                group.forEach(groupedBlock -> groupedVisualBlocks.add(groupedBlock.id()));
            } else if (block.confidence() < LOW_CONFIDENCE && isReadableContent(block)) {
                candidates.add(candidate("LOW_CONFIDENCE_TEXT", List.of(block)));
            }
        }
        return merge(candidates);
    }

    private void detectImpossibleLaneTransitions(List<DocumentBlock> blocks, List<Candidate> result) {
        DocumentBlock previous = null;
        for (DocumentBlock block : blocks) {
            if (previous == null || block.page() != previous.page()
                    || block.layoutLane() == DocumentLayoutLane.FULL) {
                previous = isFlow(block) ? block : null;
                continue;
            }
            if (!isFlow(block)) continue;
            if (previous.layoutLane() == DocumentLayoutLane.RIGHT
                    && block.layoutLane() == DocumentLayoutLane.LEFT
                    && block.bbox().y() <= previous.bbox().bottom() + 0.005) {
                result.add(candidate("READING_ORDER", List.of(previous, block)));
            }
            previous = block;
        }
    }

    private Candidate candidate(String issueType, List<DocumentBlock> blocks) {
        return new Candidate(issueType, blocks, blockAreas(blocks));
    }

    /**
     * Expands a weak formula crop to the containing lane while retaining the exact weak block IDs
     * as recovery targets. Full-page images remain the source of truth for visual confirmation.
     */
    private Map<Integer, List<NormalizedBoundingBox>> visualAreaBoxes(
            List<DocumentBlock> targetBlocks, List<DocumentBlock> allBlocks) {
        return visualAreaBoxes(targetBlocks, targetBlocks, allBlocks);
    }

    private Map<Integer, List<NormalizedBoundingBox>> visualAreaBoxes(
            List<DocumentBlock> targetBlocks, List<DocumentBlock> contextBlocks,
            List<DocumentBlock> allBlocks) {
        Map<String, List<DocumentBlock>> grouped = new LinkedHashMap<>();
        for (DocumentBlock block : targetBlocks) {
            grouped.computeIfAbsent(block.page() + "|" + block.layoutLane(), ignored -> new ArrayList<>())
                    .add(block);
        }
        Map<Integer, List<NormalizedBoundingBox>> areas = new LinkedHashMap<>();
        for (List<DocumentBlock> group : grouped.values()) {
            boolean formula = group.stream().anyMatch(block ->
                    block.role() == DocumentBlockRole.FORMULA && requiresVisualRecovery(block));
            if (!formula) {
                addBlockAreas(areas, contextBlocks);
                continue;
            }
            DocumentBlock first = group.get(0);
            NormalizedBoundingBox content = envelope(group);
            if (content == null) {
                addBlockAreas(areas, contextBlocks);
                continue;
            }
            NormalizedBoundingBox lane = laneBounds(allBlocks, first.page(), first.layoutLane());
            double left = lane == null ? Math.max(0, content.x() - 0.02) : lane.x();
            double right = lane == null ? Math.min(1, content.right() + 0.02) : lane.right();
            double top = Math.max(0, content.y() - FORMULA_VERTICAL_PADDING);
            double bottom = Math.min(1, content.bottom() + FORMULA_VERTICAL_PADDING);
            areas.computeIfAbsent(first.page(), ignored -> new ArrayList<>())
                    .add(new NormalizedBoundingBox(left, top, Math.max(0.001, right - left),
                            Math.max(0.001, bottom - top)));
        }
        return areas;
    }

    private NormalizedBoundingBox laneBounds(List<DocumentBlock> blocks, int page,
                                             DocumentLayoutLane lane) {
        List<DocumentBlock> laneBlocks = blocks.stream()
                .filter(block -> block.page() == page)
                .filter(this::isReadableContent)
                .filter(block -> laneMatches(block, lane))
                .toList();
        return envelope(laneBlocks);
    }

    private boolean laneMatches(DocumentBlock block, DocumentLayoutLane lane) {
        if (lane == DocumentLayoutLane.FULL) return true;
        return block.layoutLane() == lane;
    }

    private NormalizedBoundingBox envelope(List<DocumentBlock> blocks) {
        double left = 1;
        double top = 1;
        double right = 0;
        double bottom = 0;
        boolean found = false;
        for (DocumentBlock block : blocks) {
            if (block.bbox() == null) continue;
            left = Math.min(left, block.bbox().x());
            top = Math.min(top, block.bbox().y());
            right = Math.max(right, block.bbox().right());
            bottom = Math.max(bottom, block.bbox().bottom());
            found = true;
        }
        return found ? new NormalizedBoundingBox(left, top, Math.max(0.001, right - left),
                Math.max(0.001, bottom - top)) : null;
    }

    private Map<Integer, List<NormalizedBoundingBox>> blockAreas(List<DocumentBlock> blocks) {
        Map<Integer, List<NormalizedBoundingBox>> areas = new LinkedHashMap<>();
        addBlockAreas(areas, blocks);
        return areas;
    }

    private void addBlockAreas(Map<Integer, List<NormalizedBoundingBox>> areas,
                               List<DocumentBlock> blocks) {
        for (DocumentBlock block : blocks) {
            if (block.bbox() != null) {
                areas.computeIfAbsent(block.page(), ignored -> new ArrayList<>()).add(block.bbox());
            }
        }
    }

    private boolean requiresVisualRecovery(DocumentBlock block) {
        if (block.contentMode() != DocumentBlockContentMode.REGION) return false;
        boolean weakExtraction = block.text().strip().length() < 4 || block.confidence() < 0.72;
        if (block.role() == DocumentBlockRole.FORMULA) return weakExtraction;
        return block.role() == DocumentBlockRole.TABLE && block.tableText() == null && weakExtraction;
    }

    private boolean isReadableContent(DocumentBlock block) {
        return block.role() == DocumentBlockRole.BODY
                || block.role() == DocumentBlockRole.FORMULA
                || block.role() == DocumentBlockRole.TABLE
                || block.role() == DocumentBlockRole.CAPTION;
    }

    private boolean isFlow(DocumentBlock block) {
        if (!isReadableContent(block) || block.bbox() == null || block.bbox().width() < 0.08) return false;
        DocumentLayoutLane lane = block.layoutLane();
        if (lane != DocumentLayoutLane.LEFT && lane != DocumentLayoutLane.RIGHT) return false;
        return block.text().codePoints().filter(Character::isLetterOrDigit).limit(8).count() >= 8;
    }

    private List<LayoutUncertainRegion> merge(List<Candidate> candidates) {
        List<Candidate> merged = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Candidate target = merged.stream().filter(existing -> overlaps(existing, candidate)).findFirst().orElse(null);
            if (target == null) {
                merged.add(candidate);
            } else {
                target.blocks.addAll(candidate.blocks);
                for (Map.Entry<Integer, List<NormalizedBoundingBox>> entry : candidate.areaBoxes.entrySet()) {
                    target.areaBoxes.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                            .addAll(entry.getValue());
                }
                if ("READING_ORDER".equals(candidate.issueType)) target.issueType = candidate.issueType;
            }
        }
        List<LayoutUncertainRegion> result = new ArrayList<>();
        int ordinal = 1;
        for (Candidate candidate : merged) {
            List<DocumentBlock> unique = new ArrayList<>(new LinkedHashSet<>(candidate.blocks));
            unique.sort(Comparator.comparingInt(DocumentBlock::readingOrder));
            List<LayoutUncertainRegion.PageArea> areas = candidate.areaBoxes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new LayoutUncertainRegion.PageArea(entry.getKey(),
                            List.copyOf(entry.getValue())))
                    .toList();
            String raw = unique.stream().map(DocumentBlock::text).filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
            int page = areas.get(0).page();
            result.add(new LayoutUncertainRegion("lr-p" + page + "-" + String.format("%03d", ordinal++),
                    candidate.issueType, unique.stream().map(DocumentBlock::id).toList(), areas, raw));
        }
        return List.copyOf(result);
    }

    private boolean overlaps(Candidate left, Candidate right) {
        for (DocumentBlock a : left.blocks) {
            for (DocumentBlock b : right.blocks) {
                if (a.id().equals(b.id())) return true;
                if (a.page() == b.page() && near(a.bbox(), b.bbox())) return true;
            }
        }
        return false;
    }

    private boolean near(NormalizedBoundingBox a, NormalizedBoundingBox b) {
        if (a == null || b == null) return false;
        double horizontalGap = Math.max(0, Math.max(a.x(), b.x()) - Math.min(a.right(), b.right()));
        double verticalGap = Math.max(0, Math.max(a.y(), b.y()) - Math.min(a.bottom(), b.bottom()));
        return horizontalGap <= 0.02 && verticalGap <= 0.025;
    }

    private static final class Candidate {
        private String issueType;
        private final List<DocumentBlock> blocks;
        private final Map<Integer, List<NormalizedBoundingBox>> areaBoxes;

        private Candidate(String issueType, List<DocumentBlock> blocks,
                          Map<Integer, List<NormalizedBoundingBox>> areaBoxes) {
            this.issueType = issueType;
            this.blocks = new ArrayList<>(blocks);
            this.areaBoxes = new LinkedHashMap<>();
            areaBoxes.forEach((page, boxes) -> this.areaBoxes.put(page, new ArrayList<>(boxes)));
        }
    }
}
