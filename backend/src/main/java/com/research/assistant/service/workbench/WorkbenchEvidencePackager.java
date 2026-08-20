package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Final deterministic budget and de-duplication boundary before model input and persistence. */
@Component
public class WorkbenchEvidencePackager {

    public List<LayoutEvidence> pack(List<LayoutEvidence> evidence,
                                     int maxEvidence,
                                     int maxCharacters) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        int safeMax = Math.max(1, Math.min(80, maxEvidence));
        int safeCharacters = Math.max(1_000, Math.min(60_000, maxCharacters));
        List<LayoutEvidence> logicalEvidence = mergeWrappedEvidence(evidence);
        double topUnselected = logicalEvidence.stream().filter(item -> !item.selected())
                .mapToDouble(LayoutEvidence::score).max().orElse(0);
        double relativeFloor = topUnselected <= 0 ? 0 : Math.max(0.08, topUnselected * 0.28);
        List<LayoutEvidence> ordered = logicalEvidence.stream()
                .sorted(Comparator.comparing((LayoutEvidence item) -> !item.selected())
                        .thenComparing(Comparator.comparingDouble(LayoutEvidence::score).reversed())
                        .thenComparingInt(LayoutEvidence::page)
                        .thenComparingInt(LayoutEvidence::readingOrder))
                .toList();
        List<LayoutEvidence> result = new ArrayList<>();
        int characters = 0;
        for (LayoutEvidence candidate : ordered) {
            if (result.size() >= safeMax) break;
            if (!candidate.selected() && candidate.score() < relativeFloor) continue;
            if (result.stream().anyMatch(existing -> duplicate(existing, candidate))) continue;
            int next = characters + candidate.text().length() + candidate.structuredContent().length();
            if (!candidate.selected() && !result.isEmpty() && next > safeCharacters) continue;
            result.add(candidate);
            characters = next;
        }
        return List.copyOf(result);
    }

    private List<LayoutEvidence> mergeWrappedEvidence(List<LayoutEvidence> evidence) {
        List<LayoutEvidence> ordered = evidence.stream()
                .sorted(Comparator.comparing(LayoutEvidence::paperId)
                        .thenComparingInt(LayoutEvidence::page)
                        .thenComparingInt(LayoutEvidence::readingOrder))
                .toList();
        List<LayoutEvidence> result = new ArrayList<>();
        for (LayoutEvidence current : ordered) {
            if (!result.isEmpty() && wrappedContinuation(result.get(result.size() - 1), current)) {
                LayoutEvidence previous = result.remove(result.size() - 1);
                result.add(merge(previous, current));
            } else {
                result.add(current);
            }
        }
        return List.copyOf(result);
    }

    private boolean wrappedContinuation(LayoutEvidence first, LayoutEvidence second) {
        if (first.selected() || second.selected()
                || first.contentMode() != DocumentBlockContentMode.TEXT
                || second.contentMode() != DocumentBlockContentMode.TEXT
                || !first.paperId().equals(second.paperId()) || first.page() != second.page()
                || second.readingOrder() - first.readingOrder() < 1
                || second.readingOrder() - first.readingOrder() > 3) return false;
        String left = first.text().stripTrailing();
        String right = second.text().stripLeading();
        if (left.isBlank() || right.isBlank() || left.matches("(?s).*[.!?。！？]$")) return false;
        boolean statement = left.matches("(?is).*(?:theorem|lemma|proposition|corollary)\\s+\\d+.*");
        boolean lowerContinuation = Character.isLowerCase(right.codePointAt(0));
        if (!statement && !lowerContinuation) return false;
        return sameColumn(first.bbox(), second.bbox()) && verticalGap(first.bbox(), second.bbox()) <= .03;
    }

    private LayoutEvidence merge(LayoutEvidence first, LayoutEvidence second) {
        List<NormalizedBoundingBox> boxes = new ArrayList<>();
        boxes.addAll(first.locator().targetBoxes());
        boxes.addAll(second.locator().targetBoxes());
        NormalizedBoundingBox bbox = union(boxes);
        String text = (first.text().stripTrailing() + " " + second.text().stripLeading())
                .replaceAll("\\s+", " ").trim();
        return new LayoutEvidence(first.evidenceId(), first.paperId(),
                "logical-span:" + first.blockId() + "|" + second.blockId(), first.page(), bbox,
                first.role(), first.readingOrder(), first.sectionPath(), text,
                Math.max(first.score(), second.score()), false,
                Math.min(first.confidence(), second.confidence()), first.documentHash(),
                first.parserVersion(), DocumentBlockContentMode.TEXT, "", List.of(), List.of(),
                first.origin(), new EvidenceLocator(bbox, boxes,
                        first.locator().targetText() + "\n" + second.locator().targetText(),
                        EvidenceLocator.Precision.TEXT_SPAN),
                java.util.stream.Stream.concat(first.retrievalRoutes().stream(), second.retrievalRoutes().stream())
                        .distinct().toList());
    }

    private boolean sameColumn(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        double overlap = Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        return overlap / Math.max(.0001, Math.min(first.width(), second.width())) >= .45;
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

    private boolean duplicate(LayoutEvidence first, LayoutEvidence second) {
        if (!first.paperId().equals(second.paperId())) return false;
        if (first.evidenceId().equals(second.evidenceId())) return true;
        if (first.page() != second.page()) return false;
        if (first.contentMode() == DocumentBlockContentMode.REGION
                || second.contentMode() == DocumentBlockContentMode.REGION) {
            return first.role() == second.role() && overlapRatio(first.bbox(), second.bbox()) >= 0.78;
        }
        String left = normalize(first.text());
        String right = normalize(second.text());
        if (left.isBlank() || right.isBlank()) return overlapRatio(first.bbox(), second.bbox()) >= 0.90;
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        boolean contained = shorter.length() >= 24 && longer.contains(shorter)
                && (double) shorter.length() / longer.length() >= 0.72;
        return contained || (left.equals(right) && overlapRatio(first.bbox(), second.bbox()) >= 0.40);
    }

    private double overlapRatio(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        if (first == null || second == null) return 0;
        double width = Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.x(), second.x()));
        double height = Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.y(), second.y()));
        double intersection = width * height;
        double smaller = Math.min(first.width() * first.height(), second.width() * second.height());
        return smaller <= 0 ? 0 : intersection / smaller;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+", "");
    }
}
