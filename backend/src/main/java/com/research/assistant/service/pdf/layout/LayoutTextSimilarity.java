package com.research.assistant.service.pdf.layout;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class LayoutTextSimilarity {

    private LayoutTextSimilarity() {
    }

    public static double queryCoverage(String query, String candidate) {
        String normalizedQuery = compact(query);
        String normalizedCandidate = compact(candidate);
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
            return 0;
        }
        if (normalizedCandidate.contains(normalizedQuery)) {
            return 1;
        }

        Set<String> queryTokens = tokens(query);
        Set<String> candidateTokens = tokens(candidate);
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) {
            return prefixSimilarity(normalizedQuery, normalizedCandidate);
        }
        long matched = queryTokens.stream().filter(candidateTokens::contains).count();
        return (double) matched / queryTokens.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        Arrays.stream(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                        .toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .map(String::trim)
                .filter(token -> token.length() > 1)
                .forEach(result::add);
        return result;
    }

    private static String compact(String value) {
        return LayoutTextNormalizer.compact(value);
    }

    private static double prefixSimilarity(String first, String second) {
        int limit = Math.min(first.length(), second.length());
        if (limit == 0) {
            return 0;
        }
        int same = 0;
        while (same < limit && first.charAt(same) == second.charAt(same)) {
            same++;
        }
        return Math.min(1, (double) same / Math.max(4, first.length()));
    }
}
