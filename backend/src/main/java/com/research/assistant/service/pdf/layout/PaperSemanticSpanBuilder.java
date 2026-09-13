package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight paragraph reconstruction shared by paper understanding and
 * source lookup. It is deliberately deterministic and never calls a model.
 */
@Component
public class PaperSemanticSpanBuilder {

    private static final FigureCaptionClassifier FIGURE_CAPTIONS = new FigureCaptionClassifier();
    private static final int MAX_BLOCKS_PER_SPAN = 12;
    private static final int MAX_CHARACTERS_PER_SPAN = 1_600;
    private static final Pattern TABLE_CAPTION = Pattern.compile(
            "^table\\s*[\\dIVX]+[.:]\\s+.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGURE_REFERENCE_SENTENCE = Pattern.compile(
            "^fig(?:ure)?\\.?\\s*[\\dIVX]+\\s+(?:shows|exhibits|illustrates|depicts|presents|compares|demonstrates|plots|provides)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBHEADING = Pattern.compile(
            "^(?:[A-Z]|[IVX]{1,5})\\.\\s+[A-Z].{0,100}$");
    private static final Pattern TERMINAL_SENTENCE = Pattern.compile(
            ".*[.!?][\\]\\)\"']?$", Pattern.DOTALL);
    private static final Pattern UPPERCASE_START = Pattern.compile("^[\"'(\\[]?[A-Z0-9].*");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^(?:[•▪◦]|[-–—]\\s|\\(?\\d{1,2}[.)]\\s|\\(?[a-z][.)]\\s).+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_ENTRY = Pattern.compile(
            "^\\s*\\[\\d{1,4}[a-z]?\\]\\s+.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_BIBLIOGRAPHY_ENTRY = Pattern.compile(
            "\\[\\d{1,4}[a-z]?\\]\\s+(?:(?:[A-Z]\\.)\\s*){1,3}[\\p{L}][\\p{L}'’\\-]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}]{2,}");

    public List<PaperSemanticSpan> build(PaperLayoutArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("layout artifact is required");
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .filter(this::isEvidenceEligible)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        List<PaperSemanticSpan> result = new ArrayList<>();
        Draft current = null;
        int currentPage = -1;
        int pageOrdinal = 0;
        for (DocumentBlock block : ordered) {
            DocumentBlockRole role = effectiveRole(block, artifact.blocks());
            if (block.page() != currentPage) {
                if (current != null && canContinueAcrossPage(current, block, role)) {
                    current.add(block, content(block));
                    currentPage = block.page();
                    pageOrdinal = 0;
                    continue;
                }
                if (current != null) result.add(current.finish());
                current = null;
                currentPage = block.page();
                pageOrdinal = 0;
            }
            if (current != null && (canMerge(current, block, role)
                    || canMergeAcrossColumns(current, block, role))) {
                current.add(block, content(block));
                continue;
            }
            if (current != null) result.add(current.finish());
            String id = "p%d-s%04d".formatted(block.page(), pageOrdinal++);
            current = new Draft(id, block, role, content(block));
        }
        if (current != null) result.add(current.finish());
        return mergeCrossColumnContinuations(result);
    }

    private boolean canContinueAcrossPage(Draft previous,
                                          DocumentBlock current,
                                          DocumentBlockRole currentRole) {
        if (current.page() != previous.last().page() + 1
                || previous.role != currentRole
                || (currentRole != DocumentBlockRole.BODY
                && currentRole != DocumentBlockRole.ABSTRACT)
                || !previous.sectionPath.equals(current.sectionPath())) return false;
        DocumentBlock prior = previous.last();
        if (prior.bbox().bottom() < .84 || current.bbox().y() > .16) return false;
        String before = previous.text.stripTrailing();
        String after = content(current).stripLeading();
        if (before.isBlank() || after.isBlank()
                || TERMINAL_SENTENCE.matcher(before).matches()
                || LIST_ITEM.matcher(after).matches()
                || containsReferenceEntry(after)
                || FIGURE_REFERENCE_SENTENCE.matcher(after).matches()) return false;
        boolean hyphenatedWord = before.endsWith("-") && !before.endsWith("--")
                && after.codePoints().findFirst().stream().anyMatch(Character::isLowerCase);
        if (!proseBoundary(prior, before, hyphenatedWord)
                || !proseBoundary(current, after, hyphenatedWord)) return false;
        if (hyphenatedWord) return true;
        int first = after.codePointAt(0);
        return Character.isLowerCase(first) || ",;:)]}".indexOf(first) >= 0
                || containsCjk(before) && containsCjk(after);
    }

    /** Rejects page-edge labels, axes and compact formula fragments without naming paper formats. */
    private boolean proseBoundary(DocumentBlock block, String text, boolean hyphenatedWord) {
        if (block.mathProfile() != null
                && block.mathProfile().level() == MathContentLevel.MATH_RICH) return false;
        long cjk = text.codePoints().filter(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN).count();
        if (cjk >= 8) return true;
        long letters = text.codePoints().filter(Character::isLetter).count();
        Matcher words = Pattern.compile("[\\p{L}]{2,}").matcher(text);
        int wordCount = 0;
        while (words.find() && wordCount < 3) wordCount++;
        return letters >= (hyphenatedWord ? 8 : 14)
                && wordCount >= (hyphenatedWord ? 2 : 3);
    }

    private boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    public DocumentBlockRole effectiveRole(DocumentBlock block) {
        return effectiveRole(block, List.of(block));
    }

    private DocumentBlockRole effectiveRole(DocumentBlock block,
                                            List<DocumentBlock> contextBlocks) {
        String text = content(block);
        if (SUBHEADING.matcher(text).matches()) return DocumentBlockRole.HEADING;
        if (FIGURE_CAPTIONS.isDiscussion(block, contextBlocks)) return DocumentBlockRole.BODY;
        if (FIGURE_CAPTIONS.caption(block, contextBlocks).isPresent()
                || FIGURE_CAPTIONS.isCaptionContinuation(block, contextBlocks)
                || TABLE_CAPTION.matcher(text).matches()) return DocumentBlockRole.CAPTION;
        if (block.role() != DocumentBlockRole.FORMULA) return block.role();
        if (inReferences(block)) return DocumentBlockRole.REFERENCE;
        boolean structuredMath = block.latex() != null && !block.latex().isBlank();
        boolean detectedMath = block.mathProfile() != null && block.mathProfile().signalCount() > 0;
        boolean lightMath = block.mathProfile() == null
                || block.mathProfile().level() != MathContentLevel.MATH_RICH;
        if (!structuredMath && lightMath && proseLike(text)) return DocumentBlockRole.BODY;
        return DocumentBlockRole.FORMULA;
    }

    private boolean isPageDecoration(DocumentBlock block) {
        return block.role() == DocumentBlockRole.HEADER
                || block.role() == DocumentBlockRole.FOOTER
                || block.role() == DocumentBlockRole.MARGIN_METADATA;
    }

    private boolean isMeaningfulBlock(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.FORMULA
                || block.role() == DocumentBlockRole.TABLE
                || block.role() == DocumentBlockRole.FIGURE) return true;
        String text = content(block);
        if (!hasReadableToken(text)) return false;
        // Inline-math enrichment is useful for locating a surrounding equation, but
        // a body block that contains only a glyph/token (for example “√”, “BS” or
        // “tot”) is not a standalone semantic span and must not become evidence.
        if (block.mathProfile().signalCount() > 0 && !naturalLanguageLike(text)) return false;
        return true;
    }

    boolean isEvidenceEligible(DocumentBlock block) {
        return block != null && !content(block).isBlank()
                && !isPageDecoration(block) && isMeaningfulBlock(block);
    }

    private boolean hasReadableToken(String text) {
        return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private boolean naturalLanguageLike(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.replaceAll("\\s+", " ").strip();
        long cjk = normalized.codePoints().filter(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN).count();
        if (cjk >= 3) return true;
        long letters = normalized.codePoints().filter(Character::isLetter).count();
        Matcher words = WORD_TOKEN.matcher(normalized);
        int wordCount = 0;
        while (words.find() && wordCount < 3) wordCount++;
        return normalized.length() >= 12 && letters >= 8 && wordCount >= 2;
    }

    private boolean canMerge(Draft previous, DocumentBlock current, DocumentBlockRole currentRole) {
        if (previous.role != currentRole || !mergeable(currentRole)) return false;
        if (previous.blocks.size() >= MAX_BLOCKS_PER_SPAN
                || previous.text.length() + content(current).length() > MAX_CHARACTERS_PER_SPAN) return false;
        if (!previous.sectionPath.equals(current.sectionPath())) return false;
        if (LIST_ITEM.matcher(content(current)).matches()) return false;
        if (containsReferenceEntry(content(current))) return false;
        if (FIGURE_REFERENCE_SENTENCE.matcher(content(current)).matches()) return false;
        if (previous.text.length() >= 80
                && TERMINAL_SENTENCE.matcher(previous.text).matches()
                && UPPERCASE_START.matcher(content(current)).matches()) return false;

        DocumentBlock prior = previous.last();
        NormalizedBoundingBox first = prior.bbox();
        NormalizedBoundingBox second = current.bbox();
        double verticalGap = second.y() - first.bottom();
        double allowedGap = Math.max(0.018, Math.min(0.045,
                Math.max(first.height(), second.height()) * 1.35));
        if (verticalGap < -0.012 || verticalGap > allowedGap) return false;

        double overlap = Math.max(0, Math.min(first.right(), second.right())
                - Math.max(first.x(), second.x()));
        double smallerWidth = Math.max(0.001, Math.min(first.width(), second.width()));
        boolean sameColumn = overlap / smallerWidth >= 0.55
                || Math.abs(first.x() - second.x()) <= 0.06;
        return sameColumn;
    }

    /**
     * Handles the one unambiguous body-text split that PDF layout extraction can
     * create at a two-column boundary: a hyphenated word ends at the bottom of
     * the left column and continues with a lower-case token at the top of the
     * right column.  The guard is intentionally strict so independent columns
     * are never flattened merely because they are adjacent in reading order.
     */
    private boolean canMergeAcrossColumns(Draft previous,
                                          DocumentBlock current,
                                          DocumentBlockRole currentRole) {
        if (previous.role != currentRole
                || (currentRole != DocumentBlockRole.BODY
                && currentRole != DocumentBlockRole.ABSTRACT)
                || previous.blocks.size() >= MAX_BLOCKS_PER_SPAN
                || previous.text.length() + content(current).length() > MAX_CHARACTERS_PER_SPAN
                || !previous.sectionPath.equals(current.sectionPath())) return false;
        DocumentBlock prior = previous.last();
        if (prior.page() != current.page()) return false;
        String before = previous.text.stripTrailing();
        String after = content(current).stripLeading();
        if (before.isBlank() || after.isBlank()
                || !before.endsWith("-") || before.endsWith("--")
                || !Character.isLowerCase(after.codePointAt(0))
                || after.indexOf('=') >= 0
                || LIST_ITEM.matcher(after).matches()
                || containsReferenceEntry(after)
                || FIGURE_REFERENCE_SENTENCE.matcher(after).matches()) return false;
        if (!proseBoundary(prior, before, true) || !proseBoundary(current, after, true)) return false;

        NormalizedBoundingBox first = prior.bbox();
        NormalizedBoundingBox second = current.bbox();
        // LTR reading order: the previous fragment is on the left and ends near
        // the column bottom; the continuation begins near the top of the right.
        if (first.x() >= second.x() || first.right() > second.x() + .08) return false;
        return first.bottom() >= .68 && second.y() <= .72
                && second.y() < first.y() - .10;
    }

    private List<PaperSemanticSpan> mergeCrossColumnContinuations(List<PaperSemanticSpan> spans) {
        if (spans.size() < 2) return List.copyOf(spans);
        List<PaperSemanticSpan> merged = new ArrayList<>(spans);
        java.util.Set<Integer> removed = new java.util.HashSet<>();
        for (int firstIndex = 0; firstIndex < spans.size(); firstIndex++) {
            if (removed.contains(firstIndex)) continue;
            PaperSemanticSpan first = merged.get(firstIndex);
            for (int nextIndex = firstIndex + 1; nextIndex < spans.size(); nextIndex++) {
                if (removed.contains(nextIndex)) continue;
                PaperSemanticSpan next = spans.get(nextIndex);
                if (!canMergeAcrossColumns(first, next)
                        || !interveningBlocksAreLayoutNoise(spans, firstIndex, nextIndex)) continue;
                List<DocumentBlock> blocks = new ArrayList<>(first.blocks());
                blocks.addAll(next.blocks());
                merged.set(firstIndex, new PaperSemanticSpan(first.id(), first.page(), first.role(),
                        first.sectionPath(), join(first.text(), next.text()), blocks));
                removed.add(nextIndex);
                break;
            }
        }
        return java.util.stream.IntStream.range(0, merged.size())
                .filter(index -> !removed.contains(index))
                .mapToObj(merged::get)
                .sorted(Comparator.comparingInt(span -> span.blocks().get(0).readingOrder()))
                .toList();
    }

    private boolean canMergeAcrossColumns(PaperSemanticSpan previous, PaperSemanticSpan current) {
        if (previous.role() != current.role()
                || (current.role() != DocumentBlockRole.BODY
                && current.role() != DocumentBlockRole.ABSTRACT)
                || !previous.sectionPath().equals(current.sectionPath())) return false;
        DocumentBlock prior = previous.blocks().get(previous.blocks().size() - 1);
        DocumentBlock next = current.blocks().get(0);
        if (prior.page() != next.page()) return false;
        String before = previous.text().stripTrailing();
        String after = content(next).stripLeading();
        if (before.isBlank() || after.isBlank() || !before.endsWith("-") || before.endsWith("--")
                || !Character.isLowerCase(after.codePointAt(0)) || after.indexOf('=') >= 0
                || LIST_ITEM.matcher(after).matches() || containsReferenceEntry(after)
                || FIGURE_REFERENCE_SENTENCE.matcher(after).matches()) return false;
        if (!proseBoundary(prior, before, true) || !proseBoundary(next, after, true)) return false;
        NormalizedBoundingBox first = prior.bbox();
        NormalizedBoundingBox second = next.bbox();
        if (first.x() >= second.x() || first.right() > second.x() + .08) return false;
        return first.bottom() >= .68 && second.y() <= .72 && second.y() < first.y() - .10;
    }

    private boolean interveningBlocksAreLayoutNoise(List<PaperSemanticSpan> spans,
                                                    int firstIndex,
                                                    int nextIndex) {
        for (int index = firstIndex + 1; index < nextIndex; index++) {
            for (DocumentBlock block : spans.get(index).blocks()) {
                if (block.role() == DocumentBlockRole.FIGURE
                        || block.role() == DocumentBlockRole.CAPTION
                        || block.role() == DocumentBlockRole.HEADER
                        || block.role() == DocumentBlockRole.FOOTER
                        || block.role() == DocumentBlockRole.MARGIN_METADATA) continue;
                if (block.role() != DocumentBlockRole.BODY || !looksLikeLayoutNoise(content(block))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean looksLikeLayoutNoise(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (value.isBlank()) return true;
        long digits = value.codePoints().filter(Character::isDigit).count();
        long letters = value.codePoints().filter(Character::isLetter).count();
        double digitRatio = (double) digits / Math.max(1, digits + letters);
        return value.indexOf('=') >= 0 || digits >= 3 && digitRatio >= .10
                || value.length() <= 24 && digits >= 1;
    }

    private boolean containsReferenceEntry(String text) {
        return REFERENCE_ENTRY.matcher(text).matches()
                || INLINE_BIBLIOGRAPHY_ENTRY.matcher(text).find();
    }

    private boolean mergeable(DocumentBlockRole role) {
        return role == DocumentBlockRole.BODY || role == DocumentBlockRole.ABSTRACT
                || role == DocumentBlockRole.CAPTION;
    }

    private boolean inReferences(DocumentBlock block) {
        return block.sectionPath().stream().anyMatch(value -> {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return normalized.contains("reference") || normalized.contains("bibliograph")
                    || normalized.contains("参考文献");
        });
    }

    private boolean proseLike(String text) {
        if (text == null || text.length() < 28) return false;
        long letters = text.codePoints().filter(Character::isLetter).count();
        long spaces = text.chars().filter(Character::isWhitespace).count();
        return letters >= 16 && spaces >= 4;
    }

    private String content(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.TABLE
                && block.tableText() != null && !block.tableText().isBlank()) return block.tableText().strip();
        if (block.role() == DocumentBlockRole.FORMULA
                && block.latex() != null && !block.latex().isBlank()) return block.latex().strip();
        return block.text() == null ? "" : block.text().strip();
    }

    private static String join(String previous, String next) {
        if (previous.endsWith("-") && !previous.endsWith("--")
                && !next.isBlank() && Character.isLowerCase(next.codePointAt(0))) {
            return previous.substring(0, previous.length() - 1) + next;
        }
        return previous + " " + next;
    }

    private static final class Draft {
        private final String id;
        private final DocumentBlockRole role;
        private final List<String> sectionPath;
        private final List<DocumentBlock> blocks = new ArrayList<>();
        private String text;

        private Draft(String id, DocumentBlock block, DocumentBlockRole role, String text) {
            this.id = id;
            this.role = role;
            this.sectionPath = block.sectionPath();
            this.text = text;
            blocks.add(block);
        }

        private void add(DocumentBlock block, String value) {
            text = join(text, value);
            blocks.add(block);
        }

        private DocumentBlock last() {
            return blocks.get(blocks.size() - 1);
        }

        private PaperSemanticSpan finish() {
            return new PaperSemanticSpan(id, blocks.get(0).page(), role,
                    sectionPath, text, blocks);
        }
    }
}
