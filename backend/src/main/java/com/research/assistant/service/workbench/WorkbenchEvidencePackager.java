package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
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
        double topUnselected = evidence.stream().filter(item -> !item.selected())
                .mapToDouble(LayoutEvidence::score).max().orElse(0);
        double relativeFloor = topUnselected <= 0 ? 0 : Math.max(0.08, topUnselected * 0.28);
        List<LayoutEvidence> ordered = evidence.stream()
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
