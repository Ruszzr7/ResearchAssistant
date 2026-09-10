package com.research.assistant.service.agent.source;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Deterministic guard for text that is allowed to become a user-visible citation.
 *
 * <p>Layout extraction can leave isolated glyphs or short OCR tokens in the
 * semantic index. They may still be useful while reconstructing a formula, but
 * they are not meaningful evidence by themselves. Structured formula/table/
 * figure sources are intentionally handled by their own visual/format contract.
 */
public final class SourceEvidenceQuality {

    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}]{2,}");

    private SourceEvidenceQuality() {
    }

    public static boolean usableForCitation(SourceObject source) {
        if (source == null) return false;
        if (source.contentType() == SourceContentType.FORMULA) return usableFormula(source);
        if (source.contentType() != SourceContentType.TEXT) return true;
        return usableText(source.rawContent());
    }

    public static String status(SourceObject source) {
        if (!usableForCitation(source)) return "REJECTED";
        if (source.contentType() == SourceContentType.FORMULA
                && !Boolean.parseBoolean(source.provenance().getOrDefault("textReliable", "false"))) {
            return "VISUAL_ONLY";
        }
        return "VERIFIED";
    }

    private static boolean usableFormula(SourceObject source) {
        String text = source.rawContent() == null ? "" : source.rawContent().strip();
        if (text.isBlank()) return false;
        if (Boolean.parseBoolean(source.provenance().getOrDefault("textReliable", "false"))) return true;
        // A formula number does not make an unreadable OCR fragment evidence.  In
        // particular, an isolated glyph such as "√" used to enter the evidence
        // list merely because it had a printed number.  Keep numbered formulas
        // only when the extracted region contains at least a small amount of
        // actual symbolic content; the page/number locator remains available in
        // the layout artifact for visual inspection.
        String expression = text.replaceAll("\\(\\d{1,4}[a-z]?\\)", "");
        long alphanumeric = expression.codePoints().filter(Character::isLetterOrDigit).count();
        if (alphanumeric >= 2) return true;
        String symbols = expression.replaceAll("[\\p{L}\\p{N}\\s()\\[\\]{}.,;:]+", "");
        return expression.length() >= 6 && symbols.length() >= 2;
    }

    public static boolean usableText(String value) {
        if (value == null) return false;
        String text = value.replaceAll("\\s+", " ").strip();
        if (text.length() < 4) return false;
        long alphanumeric = text.codePoints().filter(Character::isLetterOrDigit).count();
        if (alphanumeric < 4) return false;
        long cjk = text.codePoints().filter(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN).count();
        if (cjk >= 3) return true;
        Matcher words = WORD_TOKEN.matcher(text);
        int wordCount = 0;
        while (words.find() && wordCount < 3) wordCount++;
        return wordCount >= 1;
    }
}
