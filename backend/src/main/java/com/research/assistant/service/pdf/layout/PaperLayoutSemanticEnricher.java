package com.research.assistant.service.pdf.layout;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministically enriches line-level geometry with semantic roles.
 *
 * <p>The classifier is intentionally bounded and explainable. Metadata hints
 * can strengthen title/author detection, but repeated page furniture,
 * headings, references and evidence exclusions never depend on an LLM.</p>
 */
@Component
public class PaperLayoutSemanticEnricher {

    static final String VERSION = "semantic-v6";

    private static final Pattern ABSTRACT_START = Pattern.compile(
            "(?i)^\\s*(?:abstract|summary)\\b[\\s.:-]*");
    private static final Pattern KEYWORDS = Pattern.compile(
            "(?i)^\\s*(?:index\\s+terms?|key\\s*words?)\\b[\\s.:-]*");
    private static final Pattern REFERENCES = Pattern.compile(
            "(?i)^\\s*(?:(?:[IVXLC]+|\\d+)\\.?\\s+)?(?:references|bibliography)\\s*$");
    private static final Pattern TABLE_HEADING = Pattern.compile(
            "(?i)^\\s*table\\s+(?:[IVXLC]+|\\d+)[.:\\s].*");
    private static final Pattern CAPTION = Pattern.compile(
            "(?i)^\\s*(?:fig(?:ure)?\\.?\\s*\\d+|table\\s+(?:[IVXLC]+|\\d+))[.:\\s].*");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "(?i)^\\s*(?:(?:[IVXLC]+|\\d+(?:\\.\\d+)*)\\.|appendix(?:\\s+[A-Z])?)\\s*[A-Z].*");
    private static final Pattern PROOF_HEADING = Pattern.compile(
            "(?i)^\\s*(?:proof\\s+of\\s+)?(?:lemma|theorem|proposition|corollary)\\s*\\d+.*");
    private static final Pattern EQUATION_NUMBER = Pattern.compile(".*\\(\\d+[a-z]?\\)\\s*$");
    private static final Pattern EQUATION_NUMBER_ANYWHERE = Pattern.compile("\\(\\d{1,4}[a-z]?\\)");
    private static final Pattern EQUATION_MENTION = Pattern.compile(
            "(?i).*(?:in|from|using|by|see|shown\\s+in|calculated\\s+by|given\\s+in|"
                    + "equation|eq\\.)\\s*\\(\\d{1,4}[a-z]?\\)\\s*[.,;:]?\\s*$");
    private static final Pattern FIRST_PAGE_FOOTNOTE = Pattern.compile(
            "(?i)^\\s*(?:manuscript\\s+received|this\\s+work\\s+was\\s+supported|"
                    + "corresponding\\s+author|the\\s+authors?\\s+(?:is|are)\\s+with)\\b.*");

    public String version() {
        return VERSION;
    }

    public PaperLayoutArtifact enrich(PaperLayoutArtifact raw, PaperLayoutHints hints) {
        if (raw == null) {
            throw new IllegalArgumentException("版面制品不能为空");
        }
        PaperLayoutHints safeHints = hints == null ? PaperLayoutHints.empty() : hints;
        List<DocumentBlock> ordered = raw.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        if (ordered.isEmpty()) {
            return withBlocks(raw, List.of());
        }

        Set<String> repeatedEdgeFingerprints = repeatedEdgeFingerprints(ordered);
        Set<String> titleBlockIds = detectTitleBlocks(ordered, safeHints.title());
        Set<String> authorBlockIds = detectAuthorBlocks(ordered, titleBlockIds, safeHints.authors());
        List<DocumentBlock> classified = classifyLines(
                ordered, repeatedEdgeFingerprints, titleBlockIds, authorBlockIds);
        return withBlocks(raw, mergeParagraphs(classified));
    }

    private PaperLayoutArtifact withBlocks(PaperLayoutArtifact raw, List<DocumentBlock> blocks) {
        String semanticVersion = raw.parserVersion().endsWith("+" + VERSION)
                ? raw.parserVersion()
                : raw.parserVersion() + "+" + VERSION;
        return new PaperLayoutArtifact(
                raw.paperId(),
                raw.documentHash(),
                semanticVersion,
                raw.layoutConfidence(),
                raw.generatedAt(),
                raw.pageCount(),
                blocks,
                raw.provenance()
        );
    }

    private Set<String> repeatedEdgeFingerprints(List<DocumentBlock> blocks) {
        Map<String, Set<Integer>> pagesByFingerprint = new HashMap<>();
        for (DocumentBlock block : blocks) {
            if (!isNearPageEdge(block)) {
                continue;
            }
            String fingerprint = furnitureFingerprint(block.text());
            if (fingerprint.isBlank()) {
                continue;
            }
            pagesByFingerprint.computeIfAbsent(fingerprint, ignored -> new HashSet<>())
                    .add(block.page());
        }
        Set<String> repeated = new HashSet<>();
        pagesByFingerprint.forEach((fingerprint, pages) -> {
            if (pages.size() >= 2) {
                repeated.add(fingerprint);
            }
        });
        return repeated;
    }

    private Set<String> detectTitleBlocks(List<DocumentBlock> blocks, String titleHint) {
        List<DocumentBlock> pageOne = blocks.stream()
                .filter(block -> block.page() == 1)
                .filter(block -> block.role() == DocumentBlockRole.BODY)
                .filter(block -> block.bbox().y() >= 0.045 && block.bbox().y() <= 0.24)
                .toList();
        if (pageOne.isEmpty()) {
            return Set.of();
        }

        double medianHeight = median(blocks.stream()
                .filter(block -> block.page() == 1)
                .filter(block -> block.role() == DocumentBlockRole.BODY)
                .filter(block -> block.bbox().y() > 0.15 && block.bbox().y() < 0.9)
                .map(block -> block.bbox().height())
                .toList());
        String normalizedHint = normalizeWords(titleHint);
        Set<String> hintTokens = tokens(titleHint);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (DocumentBlock block : pageOne) {
            String normalizedLine = normalizeWords(block.text());
            Set<String> lineTokens = tokens(block.text());
            long overlap = lineTokens.stream().filter(hintTokens::contains).count();
            boolean metadataMatch = !normalizedHint.isBlank()
                    && !normalizedLine.isBlank()
                    && (normalizedHint.contains(normalizedLine)
                    || (lineTokens.size() >= 2 && overlap >= Math.ceil(lineTokens.size() * 0.7)));
            boolean oneWordTitleTail = !normalizedHint.isBlank()
                    && lineTokens.size() == 1
                    && overlap == 1
                    && block.bbox().height() >= Math.max(0.009, medianHeight * 1.25)
                    && isCentered(block);
            boolean geometricFallback = normalizedHint.isBlank()
                    && block.text().length() >= 6
                    && block.bbox().height() >= Math.max(0.009, medianHeight * 1.35)
                    && isCentered(block);
            if (metadataMatch || oneWordTitleTail || geometricFallback) {
                result.add(block.id());
            }
        }
        return result;
    }

    private Set<String> detectAuthorBlocks(List<DocumentBlock> blocks,
                                           Set<String> titleBlockIds,
                                           String authorsHint) {
        if (titleBlockIds.isEmpty()) {
            return Set.of();
        }
        double titleBottom = blocks.stream()
                .filter(block -> titleBlockIds.contains(block.id()))
                .mapToDouble(block -> block.bbox().bottom())
                .max()
                .orElse(0.2);
        double abstractTop = blocks.stream()
                .filter(block -> block.page() == 1)
                .filter(block -> ABSTRACT_START.matcher(block.text()).find())
                .mapToDouble(block -> block.bbox().y())
                .min()
                .orElse(0.34);
        Set<String> authorTokens = tokens(authorsHint);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (DocumentBlock block : blocks) {
            if (block.page() != 1
                    || block.role() != DocumentBlockRole.BODY
                    || block.bbox().y() <= titleBottom
                    || block.bbox().y() >= Math.min(0.36, abstractTop)) {
                continue;
            }
            Set<String> lineTokens = tokens(block.text());
            long overlap = lineTokens.stream().filter(authorTokens::contains).count();
            boolean metadataMatch = !authorTokens.isEmpty() && overlap >= Math.max(1, lineTokens.size() / 4);
            if (metadataMatch || (authorsHint.isBlank() && isCentered(block))) {
                result.add(block.id());
            }
        }
        return result;
    }

    private List<DocumentBlock> classifyLines(List<DocumentBlock> blocks,
                                              Set<String> repeatedEdgeFingerprints,
                                              Set<String> titleBlockIds,
                                              Set<String> authorBlockIds) {
        List<DocumentBlock> classified = new ArrayList<>(blocks.size());
        boolean inAbstract = false;
        boolean inReferences = false;
        boolean inFirstPageFootnote = false;
        Lane firstPageFootnoteLane = null;
        List<String> sectionPath = List.of();

        for (DocumentBlock block : blocks) {
            DocumentBlockRole role = classifyFurniture(block, repeatedEdgeFingerprints);
            String text = block.text().trim();

            if (role != DocumentBlockRole.HEADER
                    && role != DocumentBlockRole.FOOTER
                    && role != DocumentBlockRole.MARGIN_METADATA) {
                Lane blockLane = lane(block);
                if (inFirstPageFootnote
                        && (block.page() != 1 || blockLane != firstPageFootnoteLane)) {
                    inFirstPageFootnote = false;
                    firstPageFootnoteLane = null;
                }
                boolean startsFirstPageFootnote = block.page() == 1
                        && FIRST_PAGE_FOOTNOTE.matcher(text).matches();

                if (startsFirstPageFootnote || (inFirstPageFootnote && block.page() == 1)) {
                    role = DocumentBlockRole.MARGIN_METADATA;
                    inAbstract = false;
                    if (startsFirstPageFootnote) {
                        inFirstPageFootnote = true;
                        firstPageFootnoteLane = blockLane;
                    }
                } else if (inReferences) {
                    role = DocumentBlockRole.REFERENCE;
                } else if (block.role() == DocumentBlockRole.REFERENCE) {
                    role = DocumentBlockRole.REFERENCE;
                } else if (REFERENCES.matcher(text).matches()) {
                    role = DocumentBlockRole.HEADING;
                    inReferences = true;
                    sectionPath = List.of(text);
                } else if (block.role() == DocumentBlockRole.TITLE) {
                    role = DocumentBlockRole.TITLE;
                } else if (block.role() == DocumentBlockRole.AUTHOR) {
                    role = DocumentBlockRole.AUTHOR;
                } else if (titleBlockIds.contains(block.id())) {
                    role = DocumentBlockRole.TITLE;
                } else if (authorBlockIds.contains(block.id())) {
                    role = DocumentBlockRole.AUTHOR;
                } else if (block.role() == DocumentBlockRole.ABSTRACT) {
                    role = DocumentBlockRole.ABSTRACT;
                    inAbstract = true;
                    sectionPath = List.of("Abstract");
                } else if (ABSTRACT_START.matcher(text).find()) {
                    role = DocumentBlockRole.ABSTRACT;
                    inAbstract = true;
                    sectionPath = List.of("Abstract");
                } else if (inAbstract && KEYWORDS.matcher(text).find()) {
                    role = DocumentBlockRole.ABSTRACT;
                } else if (!inAbstract && (block.role() == DocumentBlockRole.TABLE
                        || block.tableText() != null && !block.tableText().isBlank())) {
                    role = DocumentBlockRole.TABLE;
                } else if (!inAbstract && TABLE_HEADING.matcher(text).matches()) {
                    role = DocumentBlockRole.TABLE;
                } else if (!inAbstract && block.role() == DocumentBlockRole.FIGURE) {
                    role = DocumentBlockRole.FIGURE;
                } else if (!inAbstract && block.role() == DocumentBlockRole.CAPTION) {
                    role = DocumentBlockRole.CAPTION;
                } else if (!inAbstract && CAPTION.matcher(text).matches()) {
                    role = DocumentBlockRole.CAPTION;
                // PDFBox occasionally labels a displayed equation as HEADING when its
                // equation number is extracted on the same line.  A numbered equation
                // is an addressable formula, not a section heading; classify it before
                // the generic heading branch so the equation index can create the
                // canonical source object.
                } else if (hasNumberedEquationDefinition(text)) {
                    inAbstract = false;
                    role = DocumentBlockRole.FORMULA;
                } else if (block.role() == DocumentBlockRole.HEADING) {
                    inAbstract = false;
                    role = DocumentBlockRole.HEADING;
                    sectionPath = List.of(text);
                } else if (isHeading(text)) {
                    inAbstract = false;
                    role = DocumentBlockRole.HEADING;
                    sectionPath = List.of(text);
                } else if (inAbstract) {
                    role = DocumentBlockRole.ABSTRACT;
                } else if (block.role() == DocumentBlockRole.FORMULA
                        || block.latex() != null && !block.latex().isBlank()) {
                    role = DocumentBlockRole.FORMULA;
                } else if (looksLikeFormula(text)) {
                    role = DocumentBlockRole.FORMULA;
                } else {
                    role = DocumentBlockRole.BODY;
                }
            }

            List<String> blockSection = switch (role) {
                case TITLE, AUTHOR, HEADER, FOOTER, MARGIN_METADATA -> List.of();
                default -> sectionPath;
            };
            classified.add(copy(block, role, blockSection, block.readingOrder()));
        }
        return classified;
    }

    private DocumentBlockRole classifyFurniture(DocumentBlock block,
                                                 Set<String> repeatedEdgeFingerprints) {
        if (block.role() == DocumentBlockRole.MARGIN_METADATA) {
            return DocumentBlockRole.MARGIN_METADATA;
        }
        if (block.role() == DocumentBlockRole.HEADER || block.role() == DocumentBlockRole.FOOTER) {
            return block.role();
        }
        String fingerprint = furnitureFingerprint(block.text());
        if (repeatedEdgeFingerprints.contains(fingerprint)) {
            if (block.bbox().y() < 0.1) {
                return DocumentBlockRole.HEADER;
            }
            if (block.bbox().bottom() > 0.92) {
                return DocumentBlockRole.FOOTER;
            }
        }
        return block.role();
    }

    private List<DocumentBlock> mergeParagraphs(List<DocumentBlock> blocks) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<DocumentBlock> merged = new ArrayList<>();
        DocumentBlock current = null;
        for (DocumentBlock block : blocks) {
            if (current != null && canMerge(current, block)) {
                current = merge(current, block);
            } else {
                if (current != null) {
                    merged.add(current);
                }
                current = block;
            }
        }
        if (current != null) {
            merged.add(current);
        }

        List<DocumentBlock> renumbered = new ArrayList<>(merged.size());
        for (int index = 0; index < merged.size(); index++) {
            DocumentBlock block = merged.get(index);
            renumbered.add(copy(block, block.role(), block.sectionPath(), index));
        }
        return List.copyOf(renumbered);
    }

    private boolean canMerge(DocumentBlock previous, DocumentBlock current) {
        if (previous.page() != current.page()
                || previous.role() != current.role()
                || !previous.sectionPath().equals(current.sectionPath())
                || !isMergeable(previous.role())
                || lane(previous) != lane(current)) {
            return false;
        }
        // A numbered equation is an addressable source object. Never absorb it into the
        // theorem/proof prose above or below; doing so makes citations point at a large paragraph.
        if (hasNumberedEquationDefinition(previous.text())
                || hasNumberedEquationDefinition(current.text())) {
            return false;
        }
        double gap = current.bbox().y() - previous.bbox().bottom();
        double threshold = Math.min(0.018, Math.max(0.008, current.bbox().height() * 1.8));
        if (gap < -0.003 || gap > threshold) {
            return false;
        }
        return !(endsSentence(previous.text())
                && current.bbox().x() > previous.bbox().x() + 0.012
                && (previous.role() == DocumentBlockRole.BODY
                || previous.role() == DocumentBlockRole.REFERENCE));
    }

    private boolean isMergeable(DocumentBlockRole role) {
        return role == DocumentBlockRole.TITLE
                || role == DocumentBlockRole.AUTHOR
                || role == DocumentBlockRole.ABSTRACT
                || role == DocumentBlockRole.BODY
                || role == DocumentBlockRole.CAPTION
                || role == DocumentBlockRole.REFERENCE;
    }

    private DocumentBlock merge(DocumentBlock first, DocumentBlock second) {
        double left = Math.min(first.bbox().x(), second.bbox().x());
        double top = Math.min(first.bbox().y(), second.bbox().y());
        double right = Math.max(first.bbox().right(), second.bbox().right());
        double bottom = Math.max(first.bbox().bottom(), second.bbox().bottom());
        return new DocumentBlock(
                first.id(),
                first.page(),
                new NormalizedBoundingBox(left, top, right - left, bottom - top),
                first.role(),
                first.readingOrder(),
                first.sectionPath(),
                joinText(first.text(), second.text()),
                first.latex() != null ? first.latex() : second.latex(),
                first.tableText() != null ? first.tableText() : second.tableText(),
                Math.min(first.confidence(), second.confidence()),
                contentMode(first.role(), first.latex(), first.tableText()),
                first.mathProfile(),
                mergedLane(first.layoutLane(), second.layoutLane())
        );
    }

    private String joinText(String first, String second) {
        String left = first == null ? "" : first.stripTrailing();
        String right = second == null ? "" : second.stripLeading();
        if (left.matches(".*[A-Za-z]-$") && right.matches("^[a-z].*")) {
            return left.substring(0, left.length() - 1) + right;
        }
        return (left + " " + right).replaceAll("\\s+", " ").trim();
    }

    private DocumentBlock copy(DocumentBlock block,
                               DocumentBlockRole role,
                               List<String> sectionPath,
                               int readingOrder) {
        return new DocumentBlock(
                block.id(),
                block.page(),
                block.bbox(),
                role,
                readingOrder,
                sectionPath,
                block.text(),
                block.latex(),
                block.tableText(),
                block.confidence(),
                contentMode(role, block.latex(), block.tableText()),
                block.mathProfile(),
                block.layoutLane()
        );
    }

    private DocumentLayoutLane mergedLane(DocumentLayoutLane first, DocumentLayoutLane second) {
        return first == second ? first : DocumentLayoutLane.FULL;
    }

    private DocumentBlockContentMode contentMode(DocumentBlockRole role,
                                                 String latex,
                                                 String tableText) {
        if (role == DocumentBlockRole.FORMULA) {
            return latex == null || latex.isBlank()
                    ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.STRUCTURED;
        }
        if (role == DocumentBlockRole.TABLE) {
            return tableText == null || tableText.isBlank()
                    ? DocumentBlockContentMode.REGION : DocumentBlockContentMode.STRUCTURED;
        }
        if (role == DocumentBlockRole.FIGURE) return DocumentBlockContentMode.REGION;
        return DocumentBlockContentMode.TEXT;
    }

    private boolean isHeading(String text) {
        if (text == null || text.isBlank() || text.length() > 140) {
            return false;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (NUMBERED_HEADING.matcher(compact).matches()
                || PROOF_HEADING.matcher(compact).matches()) {
            return true;
        }
        long letters = compact.chars().filter(Character::isLetter).count();
        long upper = compact.chars().filter(Character::isUpperCase).count();
        return letters >= 4
                && compact.length() <= 80
                && upper >= Math.ceil(letters * 0.85)
                && compact.split("\\s+").length <= 12;
    }

    private boolean looksLikeFormula(String text) {
        if (text == null || text.isBlank() || text.length() > 220) {
            return false;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        long letters = compact.chars().filter(Character::isLetter).count();
        long digits = text.chars().filter(Character::isDigit).count();
        long math = text.chars().filter(ch -> "=<>±×÷∑∫√∞≈≤≥^_{}[]|".indexOf(ch) >= 0).count();
        long replacementLike = text.chars().filter(ch -> ch == '?' || ch == '�').count();
        double alphaRatio = letters / (double) Math.max(1, text.length());
        if (EQUATION_MENTION.matcher(compact).matches()) {
            return false;
        }
        return hasNumberedEquationDefinition(compact)
                || (math >= 2 && alphaRatio < 0.68)
                || (EQUATION_NUMBER.matcher(compact).matches() && math + digits >= 3)
                || (replacementLike >= 2 && math + digits >= 2 && alphaRatio < 0.55);
    }

    private boolean hasNumberedEquationDefinition(String text) {
        if (text == null || text.isBlank() || !EQUATION_NUMBER_ANYWHERE.matcher(text).find()) {
            return false;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (EQUATION_MENTION.matcher(compact).matches()) return false;
        boolean operator = compact.matches("(?s).*[=≈≃≤≥<>∑∏√+−].*")
                || compact.toLowerCase(Locale.ROOT).matches("(?s).*\\b(max|min|argmax|argmin)\\b.*");
        return operator;
    }

    private boolean isNearPageEdge(DocumentBlock block) {
        return block.role() == DocumentBlockRole.HEADER
                || block.role() == DocumentBlockRole.FOOTER
                || block.bbox().y() < 0.1
                || block.bbox().bottom() > 0.92;
    }

    private boolean isCentered(DocumentBlock block) {
        double center = block.bbox().x() + block.bbox().width() / 2;
        return Math.abs(center - 0.5) <= 0.14;
    }

    private Lane lane(DocumentBlock block) {
        double center = block.bbox().x() + block.bbox().width() / 2;
        if ((block.page() == 1 && block.bbox().y() < 0.32 && isCentered(block))
                || block.bbox().width() >= 0.55) {
            return Lane.FULL;
        }
        return center < 0.5 ? Lane.LEFT : Lane.RIGHT;
    }

    private boolean endsSentence(String text) {
        return text != null && text.stripTrailing().matches(".*[.!?。！？]$");
    }

    private String furnitureFingerprint(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("[^\\p{L}#]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeWords(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> tokens(String text) {
        String normalized = normalizeWords(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private double median(List<Double> values) {
        List<Double> sorted = values.stream()
                .filter(Double::isFinite)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.007;
        }
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
    }

    private enum Lane { LEFT, RIGHT, FULL }
}
