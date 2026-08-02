package com.research.assistant.service.pdf.layout;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LayoutTextSimilarity {

    private static final Pattern SCRIPT_RUN = Pattern.compile(
            "[\\p{IsHan}]+|[\\p{IsLatin}\\p{N}_-]+|[\\p{IsGreek}\\p{N}]+");

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
        String normalized = java.text.Normalizer.normalize(value,
                java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        Matcher matcher = SCRIPT_RUN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.length() > 1) result.add(token);
        }
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
