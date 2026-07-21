package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, JSON-serializable structure derived from a versioned layout artifact.
 * Text remains in the layout block store; this document keeps stable block references.
 */
public record PaperStructure(String schemaVersion,
                             Long paperId,
                             Source source,
                             Metadata metadata,
                             int pageCount,
                             List<PageIndex> pages,
                             List<String> readingOrder,
                             List<Section> sections,
                             List<Element> elements,
                             List<CrossPageContinuation> crossPageContinuations,
                             Statistics statistics,
                             Quality quality,
                             Instant generatedAt) {

    public static final String SCHEMA_VERSION = "paper-structure-v2";

    public PaperStructure {
        schemaVersion = safe(schemaVersion, SCHEMA_VERSION);
        pages = copy(pages);
        readingOrder = copy(readingOrder);
        sections = copy(sections);
        elements = copy(elements);
        crossPageContinuations = copy(crossPageContinuations);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    /** Compatibility constructor for callers created before page indexes were added. */
    public PaperStructure(String schemaVersion,
                          Long paperId,
                          Source source,
                          Metadata metadata,
                          int pageCount,
                          List<String> readingOrder,
                          List<Section> sections,
                          List<Element> elements,
                          List<CrossPageContinuation> crossPageContinuations,
                          Statistics statistics,
                          Quality quality,
                          Instant generatedAt) {
        this(schemaVersion, paperId, source, metadata, pageCount, List.of(),
                readingOrder, sections, elements, crossPageContinuations,
                statistics, quality, generatedAt);
    }

    public record PageIndex(int page,
                            List<String> blockIds,
                            List<String> contentBlockIds,
                            int readingOrderStart,
                            int readingOrderEnd,
                            Map<String, Integer> roleCounts) {
        public PageIndex {
            blockIds = copy(blockIds);
            contentBlockIds = copy(contentBlockIds);
            roleCounts = roleCounts == null ? Map.of() : Map.copyOf(roleCounts);
        }
    }

    public record Source(String documentHash,
                         String layoutParserVersion,
                         double layoutConfidence,
                         String selectedParser) {
        public Source {
            documentHash = safe(documentHash, "");
            layoutParserVersion = safe(layoutParserVersion, "");
            layoutConfidence = clamp(layoutConfidence);
            selectedParser = safe(selectedParser, layoutParserVersion);
        }
    }

    public record Metadata(String title,
                           List<String> authors,
                           Integer year,
                           String source,
                           String doi,
                           List<String> keywords,
                           String abstractText) {
        public Metadata {
            title = safe(title, "");
            authors = copy(authors);
            source = safe(source, "");
            doi = safe(doi, "");
            keywords = copy(keywords);
            abstractText = safe(abstractText, "");
        }
    }

    public record Section(String id,
                          String kind,
                          String heading,
                          int level,
                          String parentId,
                          List<String> path,
                          int pageStart,
                          int pageEnd,
                          int readingOrderStart,
                          int readingOrderEnd,
                          List<String> blockIds) {
        public Section {
            id = safe(id, "");
            kind = safe(kind, "SECTION");
            heading = safe(heading, "");
            parentId = safe(parentId, "");
            path = copy(path);
            blockIds = copy(blockIds);
        }
    }

    public record Element(String id,
                          String type,
                          int page,
                          int readingOrder,
                          NormalizedBoundingBox bbox,
                          String contentMode,
                          String label,
                          String content,
                          List<String> blockIds,
                          List<String> relatedBlockIds,
                          double confidence) {
        public Element {
            id = safe(id, "");
            type = safe(type, "REGION");
            contentMode = safe(contentMode, "REGION");
            label = safe(label, "");
            content = safe(content, "");
            blockIds = copy(blockIds);
            relatedBlockIds = copy(relatedBlockIds);
            confidence = clamp(confidence);
        }
    }

    public record CrossPageContinuation(String fromBlockId,
                                        String toBlockId,
                                        int fromPage,
                                        int toPage,
                                        String sectionId,
                                        double confidence,
                                        String reason) {
        public CrossPageContinuation {
            fromBlockId = safe(fromBlockId, "");
            toBlockId = safe(toBlockId, "");
            sectionId = safe(sectionId, "");
            confidence = clamp(confidence);
            reason = safe(reason, "");
        }
    }

    public record Statistics(int totalBlocks,
                             int contentBlocks,
                             int excludedBlocks,
                             int characterCount,
                             Map<String, Integer> roleCounts,
                             Map<String, Integer> contentModeCounts) {
        public Statistics {
            roleCounts = roleCounts == null ? Map.of() : Map.copyOf(roleCounts);
            contentModeCounts = contentModeCounts == null ? Map.of() : Map.copyOf(contentModeCounts);
        }
    }

    public record Quality(double layoutConfidence,
                          double averageBlockConfidence,
                          int lowConfidenceBlocks,
                          int regionOnlyElements,
                          List<String> issues) {
        public Quality {
            layoutConfidence = clamp(layoutConfidence);
            averageBlockConfidence = clamp(averageBlockConfidence);
            issues = copy(issues);
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
