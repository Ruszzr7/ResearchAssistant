package com.research.assistant.service.metadata;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutHints;
import com.research.assistant.service.pdf.layout.PaperLayoutSemanticEnricher;
import com.research.assistant.service.pdf.layout.PdfBoxPaperLayoutParser;
import com.research.assistant.service.pdf.layout.PdfDocumentFingerprint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Extracts import-time metadata with the same local PDFBox geometry used by paper understanding.
 * Embedded document information is preferred when it is usable; semantic layout blocks provide
 * a deterministic fallback for old PDFs without metadata.
 */
@Component
public class PdfLayoutMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfLayoutMetadataExtractor.class);
    private static final int MAX_TITLE = 500;
    private static final int MAX_AUTHORS = 20_000;
    private static final int MAX_ABSTRACT = PdfMetadataHeuristics.MAX_ABSTRACT_LENGTH;
    private static final int MAX_SUBJECT = 4_000;
    private static final int MAX_KEYWORDS = 4_000;
    private static final Pattern ABSTRACT_PREFIX = Pattern.compile(
            "(?is)^\\s*(?:abstract|summary|摘要)\\s*[-–—:：.]*\\s*");

    private final PdfBoxPaperLayoutParser layoutParser;
    private final PaperLayoutSemanticEnricher semanticEnricher;

    public PdfLayoutMetadataExtractor(PdfBoxPaperLayoutParser layoutParser,
                                      PaperLayoutSemanticEnricher semanticEnricher) {
        this.layoutParser = layoutParser;
        this.semanticEnricher = semanticEnricher;
    }

    public PdfDocumentMetadata extract(File file, int maxPages) {
        if (file == null || !file.isFile()) return PdfDocumentMetadata.empty();
        Embedded embedded = readEmbedded(file);
        try {
            String hash = PdfDocumentFingerprint.sha256(file);
            PaperLayoutArtifact raw = layoutParser.parseFirstPages(-1L, file, hash, Math.max(1, maxPages));
            PaperLayoutArtifact enriched = semanticEnricher.enrich(raw,
                    new PaperLayoutHints(embedded.title(), embedded.authors(), ""));
            String layoutTitle = joinFirstTitleGroup(enriched.blocks());
            String layoutAuthors = usableLayoutAuthors(stripByline(join(
                    enriched.blocks(), DocumentBlockRole.AUTHOR, MAX_AUTHORS)));
            if (layoutAuthors == null) layoutAuthors = findExplicitByline(enriched.blocks());
            String abstractBlocks = join(enriched.blocks(), DocumentBlockRole.ABSTRACT, MAX_ABSTRACT);
            String layoutAbstract = abstractBlocks == null ? null
                    : ABSTRACT_PREFIX.matcher(abstractBlocks).replaceFirst("");
            return new PdfDocumentMetadata(
                    first(embedded.title(), layoutTitle),
                    first(embedded.authors(), layoutAuthors),
                    blankToNull(layoutAbstract),
                    embedded.subject(),
                    embedded.keywords());
        } catch (RuntimeException exception) {
            log.debug("PDF 版面元数据提取失败 file={} type={}", file.getName(),
                    exception.getClass().getSimpleName());
            return new PdfDocumentMetadata(embedded.title(), embedded.authors(), null,
                    embedded.subject(), embedded.keywords());
        }
    }

    private Embedded readEmbedded(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDDocumentInformation info = document.getDocumentInformation();
            return new Embedded(
                    usableTitle(clean(info.getTitle(), MAX_TITLE)),
                    clean(info.getAuthor(), MAX_AUTHORS),
                    clean(info.getSubject(), MAX_SUBJECT),
                    clean(info.getKeywords(), MAX_KEYWORDS));
        } catch (Exception exception) {
            return new Embedded(null, null, null, null);
        }
    }

    private String join(List<DocumentBlock> blocks, DocumentBlockRole role, int maxLength) {
        String value = blocks.stream()
                .filter(block -> block.page() == 1 && block.role() == role)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .map(DocumentBlock::text)
                .map(this::normalize)
                .filter(text -> !text.isBlank())
                .reduce("", (left, right) -> left.isBlank() ? right : left + " " + right);
        if (value.length() > maxLength) value = value.substring(0, maxLength).trim();
        return blankToNull(value);
    }

    private String joinFirstTitleGroup(List<DocumentBlock> blocks) {
        List<DocumentBlock> titles = blocks.stream()
                .filter(block -> block.page() == 1 && block.role() == DocumentBlockRole.TITLE)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        if (titles.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        double previousBottom = titles.get(0).bbox().bottom();
        for (DocumentBlock block : titles) {
            if (result.length() > 0 && block.bbox().y() - previousBottom > 0.04) break;
            if (result.length() > 0) result.append(' ');
            result.append(normalize(block.text()));
            previousBottom = Math.max(previousBottom, block.bbox().bottom());
        }
        return clean(result.toString(), MAX_TITLE);
    }

    private String usableLayoutAuthors(String value) {
        if (value == null) return null;
        String cleaned = value
                .replaceFirst("(?i)\\s+[∗*†‡]+\\s*(?:google|university|institute|laboratory|department|school|college)\\b.*$", "")
                .replaceAll("(?i)(^|[,;])\\s*(?:and|&)\\s+", "$1 ")
                .trim();
        int words = cleaned.split("\\s+").length;
        return words <= 40 ? blankToNull(cleaned) : null;
    }

    private String findExplicitByline(List<DocumentBlock> blocks) {
        return blocks.stream()
                .filter(block -> block.page() == 1 && block.bbox().y() < 0.24)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .map(DocumentBlock::text)
                .map(this::normalize)
                .filter(text -> text.matches("(?i)^by\\s+.{3,240}$")
                        || text.matches("^(?:[A-Z]\\.\\s+){1,5}[A-Z][A-Za-z'’-]+$"))
                .map(this::stripByline)
                .findFirst()
                .orElse(null);
    }

    private String stripByline(String value) {
        if (value == null) return null;
        return blankToNull(value.replaceFirst("(?i)^\\s*by\\s+", ""));
    }

    private String usableTitle(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("untitled") || lower.equals("document") || lower.equals("microsoft word")
                || lower.equals("paper") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".pdf") || lower.endsWith(".dvi") || lower.endsWith(".tex")) {
            return null;
        }
        return value;
    }

    private String clean(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return null;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength).trim();
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u0000', ' ')
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? blankToNull(fallback) : preferred;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Embedded(String title, String authors, String subject, String keywords) { }
}
