package com.research.assistant.service.agent.source;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Stable identity for one physical evidence unit.
 *
 * <p>The source object id alone is not sufficient: the parser can publish two
 * objects for the same text and page region.  Text alone is deliberately not
 * sufficient either, because repeated sentences on different pages are valid
 * independent evidence.  The identity therefore combines complete text with
 * the selected physical locator geometry.</p>
 */
public final class SourceEvidenceIdentity {

    private SourceEvidenceIdentity() {
    }

    public static String key(SourceObject source, List<SourceLocator> locators) {
        if (source == null) return "";
        StringBuilder material = new StringBuilder()
                .append(source.paperId()).append('|')
                .append(source.documentHash()).append('|')
                .append(source.parserVersion()).append('|')
                .append(SourceObject.normalize(source.rawContent())).append('|');
        (locators == null ? List.<SourceLocator>of() : locators).stream()
                .sorted(Comparator.comparingInt(SourceLocator::pageNumber)
                        .thenComparing(SourceLocator::locatorId))
                .forEach(locator -> {
                    material.append(locator.pageNumber()).append(':');
                    locator.rects().stream().sorted(Comparator
                                    .comparingDouble(NormalizedBoundingBox::y)
                                    .thenComparingDouble(NormalizedBoundingBox::x)
                                    .thenComparingDouble(NormalizedBoundingBox::width)
                                    .thenComparingDouble(NormalizedBoundingBox::height))
                            .forEach(box -> material.append(Double.toHexString(box.x())).append(',')
                                    .append(Double.toHexString(box.y())).append(',')
                                    .append(Double.toHexString(box.width())).append(',')
                                    .append(Double.toHexString(box.height())).append(';'));
                    material.append('|');
                });
        return "ev_" + hash(material.toString()).substring(0, 24);
    }

    /**
     * Matches an exact physical duplicate and the common parser case where one
     * object contains the same physical block(s) as another object but with a
     * longer, reconstructed text span.  A page/rectangle match is mandatory;
     * text on another page is never collapsed.
     */
    public static boolean equivalent(SourceObject first,
                                     List<SourceLocator> firstLocators,
                                     SourceObject second,
                                     List<SourceLocator> secondLocators) {
        if (first == null || second == null
                || first.paperId() != second.paperId()
                || !first.documentHash().equals(second.documentHash())
                || !first.parserVersion().equals(second.parserVersion())) return false;
        String a = comparableText(first.rawContent());
        String b = comparableText(second.rawContent());
        if (a.length() < 40 || b.length() < 40
                || !(a.equals(b) || a.startsWith(b) || b.startsWith(a))) return false;
        return locatorSubsetMatches(firstLocators, secondLocators)
                || locatorSubsetMatches(secondLocators, firstLocators);
    }

    /**
     * Matches two formula records that point at the same printed number and physical
     * page region.  Formula OCR can be only a few glyphs long, so unlike text identity
     * this check deliberately does not require a minimum text length.
     */
    public static boolean formulaEquivalent(SourceObject first,
                                            List<SourceLocator> firstLocators,
                                            SourceObject second,
                                            List<SourceLocator> secondLocators) {
        if (!sameDocument(first, second)
                || first.contentType() != SourceContentType.FORMULA
                || second.contentType() != SourceContentType.FORMULA) return false;
        String firstNumber = normalizeFormulaNumber(first.formulaNumber());
        String secondNumber = normalizeFormulaNumber(second.formulaNumber());
        if (firstNumber.isBlank() || !firstNumber.equals(secondNumber)) return false;
        return overlappingLocators(firstLocators, secondLocators, .65);
    }

    /** Matches exact text duplicates only when their page geometry overlaps. */
    public static boolean textEquivalent(SourceObject first,
                                         List<SourceLocator> firstLocators,
                                         SourceObject second,
                                         List<SourceLocator> secondLocators) {
        if (!sameDocument(first, second) || first.contentType() != second.contentType()
                || first.contentType() == SourceContentType.FORMULA) return false;
        String firstText = SourceObject.normalize(first.rawContent()).toLowerCase(Locale.ROOT);
        String secondText = SourceObject.normalize(second.rawContent()).toLowerCase(Locale.ROOT);
        if (firstText.isBlank() || !firstText.equals(secondText)) return false;
        return overlappingLocators(firstLocators, secondLocators, .90);
    }

    private static boolean sameDocument(SourceObject first, SourceObject second) {
        return first != null && second != null
                && first.paperId() == second.paperId()
                && first.documentHash().equals(second.documentHash())
                && first.parserVersion().equals(second.parserVersion());
    }

    private static String normalizeFormulaNumber(String value) {
        return SourceObject.normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static boolean overlappingLocators(List<SourceLocator> first,
                                               List<SourceLocator> second,
                                               double threshold) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) return false;
        return first.stream().anyMatch(left -> second.stream().anyMatch(right ->
                left.pageNumber() == right.pageNumber()
                        && left.rects().stream().anyMatch(a -> right.rects().stream()
                        .anyMatch(b -> overlapRatio(a, b) >= threshold))));
    }

    private static double overlapRatio(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        double left = Math.max(first.x(), second.x());
        double top = Math.max(first.y(), second.y());
        double right = Math.min(first.right(), second.right());
        double bottom = Math.min(first.bottom(), second.bottom());
        double intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        double smaller = Math.min(first.width() * first.height(), second.width() * second.height());
        return smaller <= 0 ? 0 : intersection / smaller;
    }

    private static String comparableText(String value) {
        return SourceObject.normalize(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean locatorSubsetMatches(List<SourceLocator> subset,
                                                List<SourceLocator> superset) {
        if (subset == null || subset.isEmpty() || superset == null || superset.isEmpty()) return false;
        return subset.stream().allMatch(left -> superset.stream().anyMatch(right ->
                left.pageNumber() == right.pageNumber()
                        && left.rects().stream().allMatch(a -> right.rects().stream()
                        .anyMatch(b -> close(a, b)))));
    }

    private static boolean close(NormalizedBoundingBox first, NormalizedBoundingBox second) {
        return Math.abs(first.x() - second.x()) <= .012
                && Math.abs(first.y() - second.y()) <= .012
                && Math.abs(first.width() - second.width()) <= .012
                && Math.abs(first.height() - second.height()) <= .012;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
