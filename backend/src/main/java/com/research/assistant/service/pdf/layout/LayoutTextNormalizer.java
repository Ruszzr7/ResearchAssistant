package com.research.assistant.service.pdf.layout;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Unicode-aware normalization shared by similarity and exact-offset recovery. */
final class LayoutTextNormalizer {

    private LayoutTextNormalizer() {
    }

    static String compact(String value) {
        return normalize(value).text();
    }

    static List<SelectionBlockRange> locate(List<DocumentBlock> blocks, String query) {
        String normalizedQuery = compact(query);
        if (normalizedQuery.isBlank() || blocks.isEmpty()) return List.of();

        StringBuilder combined = new StringBuilder();
        List<SourcePosition> positions = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            NormalizedText normalized = normalize(block.text());
            combined.append(normalized.text());
            for (int index = 0; index < normalized.text().length(); index++) {
                positions.add(new SourcePosition(block.id(), normalized.starts().get(index),
                        normalized.ends().get(index)));
            }
        }
        int matchedStart = combined.indexOf(normalizedQuery);
        if (matchedStart < 0) return List.of();
        int matchedEnd = matchedStart + normalizedQuery.length();

        List<SelectionBlockRange> result = new ArrayList<>();
        String currentBlock = "";
        int rangeStart = 0;
        int rangeEnd = 0;
        for (int index = matchedStart; index < matchedEnd; index++) {
            SourcePosition position = positions.get(index);
            if (!position.blockId().equals(currentBlock)) {
                if (!currentBlock.isBlank()) {
                    result.add(new SelectionBlockRange(currentBlock, rangeStart, rangeEnd));
                }
                currentBlock = position.blockId();
                rangeStart = position.start();
            }
            rangeEnd = position.end();
        }
        if (!currentBlock.isBlank()) {
            result.add(new SelectionBlockRange(currentBlock, rangeStart, rangeEnd));
        }
        return List.copyOf(result);
    }

    private static NormalizedText normalize(String value) {
        if (value == null || value.isBlank()) return new NormalizedText("", List.of(), List.of());
        StringBuilder text = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int nextOffset = offset + Character.charCount(codePoint);
            String source = value.substring(offset, nextOffset);
            String normalized = Normalizer.normalize(source, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            for (int normalizedOffset = 0; normalizedOffset < normalized.length();) {
                int normalizedCodePoint = normalized.codePointAt(normalizedOffset);
                int normalizedWidth = Character.charCount(normalizedCodePoint);
                if (Character.isLetterOrDigit(normalizedCodePoint)) {
                    String retained = new String(Character.toChars(normalizedCodePoint));
                    text.append(retained);
                    for (int index = 0; index < retained.length(); index++) {
                        starts.add(offset);
                        ends.add(nextOffset);
                    }
                }
                normalizedOffset += normalizedWidth;
            }
            offset = nextOffset;
        }
        return new NormalizedText(text.toString(), List.copyOf(starts), List.copyOf(ends));
    }

    private record NormalizedText(String text, List<Integer> starts, List<Integer> ends) {
    }

    private record SourcePosition(String blockId, int start, int end) {
    }
}
