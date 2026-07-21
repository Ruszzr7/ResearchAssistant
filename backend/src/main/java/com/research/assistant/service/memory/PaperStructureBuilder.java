package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutArtifactProvenance;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a deterministic paper outline and region index without invoking a model. */
@Component
public class PaperStructureBuilder {

    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)*)[.)]?\\s+.*");
    private static final Pattern ROMAN_HEADING = Pattern.compile(
            "(?i)^\\s*[IVXLC]+[.)]\\s+.*");
    private static final Pattern LETTER_HEADING = Pattern.compile(
            "^\\s*[A-Z][.)]\\s+.*");
    private static final Pattern REFERENCE_HEADING = Pattern.compile(
            "(?i).*\\b(?:references|bibliography)\\b.*");
    private static final Pattern FIGURE_CAPTION = Pattern.compile(
            "(?i)^\\s*fig(?:ure)?\\.?\\s*([A-Za-z0-9.-]+).*");
    private static final Pattern TABLE_CAPTION = Pattern.compile(
            "(?i)^\\s*table\\s*([A-Za-z0-9.-]+).*");
    private static final Set<DocumentBlockRole> EXCLUDED_ROLES = Set.of(
            DocumentBlockRole.AUTHOR,
            DocumentBlockRole.HEADER,
            DocumentBlockRole.FOOTER,
            DocumentBlockRole.MARGIN_METADATA
    );
    private static final Set<DocumentBlockRole> ELEMENT_ROLES = Set.of(
            DocumentBlockRole.FIGURE,
            DocumentBlockRole.CAPTION,
            DocumentBlockRole.FORMULA,
            DocumentBlockRole.TABLE
    );
    private static final Set<DocumentBlockRole> NARRATIVE_ROLES = Set.of(
            DocumentBlockRole.ABSTRACT,
            DocumentBlockRole.BODY,
            DocumentBlockRole.REFERENCE
    );

    private final ObjectMapper objectMapper;

    public PaperStructureBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaperStructure build(Paper paper, PaperLayoutArtifact artifact) {
        if (paper == null || artifact == null) {
            throw new IllegalArgumentException("论文与版面制品不能为空");
        }
        List<DocumentBlock> ordered = artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        List<DocumentBlock> content = ordered.stream()
                .filter(block -> !EXCLUDED_ROLES.contains(block.role()))
                .toList();
        List<PaperStructure.PageIndex> pages = buildPages(ordered, content, artifact.pageCount());
        List<String> readingOrder = content.stream().map(DocumentBlock::id).toList();
        List<SectionDraft> drafts = buildSectionDrafts(content);
        List<PaperStructure.Section> sections = drafts.stream().map(SectionDraft::freeze).toList();
        Map<String, String> sectionByBlock = sectionIndex(drafts);
        List<PaperStructure.Element> elements = buildElements(content);
        List<PaperStructure.CrossPageContinuation> continuations =
                buildCrossPageContinuations(content, sectionByBlock);
        PaperStructure.Statistics statistics = statistics(ordered, content);
        PaperStructure.Quality quality = quality(artifact, content, elements, sections);
        LayoutArtifactProvenance provenance = artifact.provenance();

        return new PaperStructure(
                PaperStructure.SCHEMA_VERSION,
                paper.getId(),
                new PaperStructure.Source(
                        artifact.documentHash(),
                        artifact.parserVersion(),
                        artifact.layoutConfidence(),
                        provenance == null ? artifact.parserVersion() : provenance.selectedParser()),
                new PaperStructure.Metadata(
                        paper.getTitle(),
                        parseAuthors(paper.getAuthors()),
                        paper.getYear(),
                        paper.getSource(),
                        paper.getDoi(),
                        splitKeywords(paper.getKeywords()),
                        paper.getAbstractText()),
                artifact.pageCount(),
                pages,
                readingOrder,
                sections,
                elements,
                continuations,
                statistics,
                quality,
                artifact.generatedAt()
        );
    }

    private List<PaperStructure.PageIndex> buildPages(List<DocumentBlock> all,
                                                       List<DocumentBlock> content,
                                                       int pageCount) {
        Set<String> contentIds = content.stream().map(DocumentBlock::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<PaperStructure.PageIndex> pages = new ArrayList<>();
        for (int page = 1; page <= pageCount; page++) {
            int pageNumber = page;
            List<DocumentBlock> pageBlocks = all.stream()
                    .filter(block -> block.page() == pageNumber)
                    .toList();
            List<String> blockIds = pageBlocks.stream().map(DocumentBlock::id).toList();
            List<String> pageContentIds = pageBlocks.stream().map(DocumentBlock::id)
                    .filter(contentIds::contains).toList();
            Map<String, Integer> roleCounts = new TreeMap<>();
            pageBlocks.forEach(block -> roleCounts.merge(block.role().name(), 1, Integer::sum));
            int first = pageBlocks.stream().mapToInt(DocumentBlock::readingOrder).min().orElse(-1);
            int last = pageBlocks.stream().mapToInt(DocumentBlock::readingOrder).max().orElse(-1);
            pages.add(new PaperStructure.PageIndex(
                    pageNumber, blockIds, pageContentIds, first, last, roleCounts));
        }
        return List.copyOf(pages);
    }

    private List<SectionDraft> buildSectionDrafts(List<DocumentBlock> blocks) {
        List<SectionDraft> sections = new ArrayList<>();
        Deque<SectionDraft> hierarchy = new ArrayDeque<>();
        SectionDraft current = null;

        for (DocumentBlock block : blocks) {
            if (block.role() == DocumentBlockRole.TITLE) {
                continue;
            }
            if (block.role() == DocumentBlockRole.ABSTRACT) {
                if (current == null || !"ABSTRACT".equals(current.kind)) {
                    current = specialSection(sections, "ABSTRACT", "Abstract");
                    hierarchy.clear();
                }
                current.add(block);
                continue;
            }
            if (block.role() == DocumentBlockRole.HEADING) {
                int level = headingLevel(block.text());
                while (!hierarchy.isEmpty() && hierarchy.peekLast().level >= level) {
                    hierarchy.removeLast();
                }
                SectionDraft parent = hierarchy.peekLast();
                List<String> path = new ArrayList<>(parent == null ? List.of() : parent.path);
                path.add(cleanHeading(block.text()));
                current = new SectionDraft(
                        sectionId(sections.size()),
                        REFERENCE_HEADING.matcher(block.text()).matches() ? "REFERENCES" : "SECTION",
                        cleanHeading(block.text()),
                        level,
                        parent == null ? "" : parent.id,
                        path);
                current.add(block);
                sections.add(current);
                hierarchy.addLast(current);
                continue;
            }
            if (current == null || "ABSTRACT".equals(current.kind)) {
                current = specialSection(sections, "FRONT_MATTER", "");
                hierarchy.clear();
            }
            current.add(block);
        }
        return sections.stream().filter(section -> !section.blockIds.isEmpty()).toList();
    }

    private SectionDraft specialSection(List<SectionDraft> sections, String kind, String heading) {
        SectionDraft existing = sections.stream()
                .filter(section -> kind.equals(section.kind))
                .reduce((first, second) -> second)
                .orElse(null);
        if (existing != null && existing.readingOrderEnd == sections.stream()
                .mapToInt(section -> section.readingOrderEnd).max().orElse(-2)) {
            return existing;
        }
        SectionDraft created = new SectionDraft(
                sectionId(sections.size()), kind, heading, 1, "",
                heading.isBlank() ? List.of() : List.of(heading));
        sections.add(created);
        return created;
    }

    private Map<String, String> sectionIndex(List<SectionDraft> sections) {
        Map<String, String> result = new LinkedHashMap<>();
        for (SectionDraft section : sections) {
            for (String blockId : section.blockIds) {
                result.put(blockId, section.id);
            }
        }
        return result;
    }

    private List<PaperStructure.Element> buildElements(List<DocumentBlock> blocks) {
        List<PaperStructure.Element> result = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            if (!ELEMENT_ROLES.contains(block.role())) {
                continue;
            }
            String content = elementContent(block);
            String label = elementLabel(block);
            List<String> related = block.role() == DocumentBlockRole.CAPTION
                    ? relatedVisualBlockIds(blocks, block) : List.of();
            result.add(new PaperStructure.Element(
                    "element:" + block.id(),
                    block.role().name(),
                    block.page(),
                    block.readingOrder(),
                    block.bbox(),
                    block.contentMode().name(),
                    label,
                    limit(content, 8_000),
                    List.of(block.id()),
                    related,
                    block.confidence()));
        }
        return List.copyOf(result);
    }

    private List<String> relatedVisualBlockIds(List<DocumentBlock> blocks, DocumentBlock caption) {
        String expected = FIGURE_CAPTION.matcher(caption.text()).matches() ? "FIGURE"
                : TABLE_CAPTION.matcher(caption.text()).matches() ? "TABLE" : "";
        return blocks.stream()
                .filter(block -> block.page() == caption.page())
                .filter(block -> block.role() == DocumentBlockRole.FIGURE
                        || block.role() == DocumentBlockRole.TABLE)
                .filter(block -> expected.isBlank() || block.role().name().equals(expected))
                .filter(block -> Math.abs(block.readingOrder() - caption.readingOrder()) <= 4)
                .min(Comparator.comparingInt(block -> Math.abs(
                        block.readingOrder() - caption.readingOrder())))
                .map(block -> List.of(block.id()))
                .orElse(List.of());
    }

    private List<PaperStructure.CrossPageContinuation> buildCrossPageContinuations(
            List<DocumentBlock> blocks,
            Map<String, String> sectionByBlock) {
        List<DocumentBlock> narrative = blocks.stream()
                .filter(block -> NARRATIVE_ROLES.contains(block.role()))
                .toList();
        List<PaperStructure.CrossPageContinuation> result = new ArrayList<>();
        for (int index = 1; index < narrative.size(); index++) {
            DocumentBlock previous = narrative.get(index - 1);
            DocumentBlock current = narrative.get(index);
            String previousSection = sectionByBlock.getOrDefault(previous.id(), "");
            String currentSection = sectionByBlock.getOrDefault(current.id(), "");
            if (current.page() != previous.page() + 1
                    || !previousSection.equals(currentSection)
                    || !rolesCanContinue(previous.role(), current.role())
                    || endsParagraph(previous.text())) {
                continue;
            }
            boolean hyphenated = previous.text().stripTrailing().endsWith("-");
            result.add(new PaperStructure.CrossPageContinuation(
                    previous.id(), current.id(), previous.page(), current.page(),
                    previousSection, hyphenated ? 0.92 : 0.76,
                    hyphenated ? "HYPHENATED_PAGE_BREAK" : "OPEN_SENTENCE_PAGE_BREAK"));
        }
        return List.copyOf(result);
    }

    private PaperStructure.Statistics statistics(List<DocumentBlock> all,
                                                  List<DocumentBlock> content) {
        Map<String, Integer> roles = new TreeMap<>();
        Map<String, Integer> modes = new TreeMap<>();
        int characters = 0;
        for (DocumentBlock block : content) {
            roles.merge(block.role().name(), 1, Integer::sum);
            modes.merge(block.contentMode().name(), 1, Integer::sum);
            characters += block.text().length();
            if (block.latex() != null) characters += block.latex().length();
            if (block.tableText() != null) characters += block.tableText().length();
        }
        return new PaperStructure.Statistics(
                all.size(), content.size(), all.size() - content.size(),
                characters, roles, modes);
    }

    private PaperStructure.Quality quality(PaperLayoutArtifact artifact,
                                            List<DocumentBlock> content,
                                            List<PaperStructure.Element> elements,
                                            List<PaperStructure.Section> sections) {
        double average = content.stream().mapToDouble(DocumentBlock::confidence).average().orElse(0);
        int lowConfidence = (int) content.stream().filter(block -> block.confidence() < 0.65).count();
        int regionOnly = (int) elements.stream()
                .filter(element -> DocumentBlockContentMode.REGION.name().equals(element.contentMode()))
                .count();
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        if (artifact.provenance() != null) issues.addAll(artifact.provenance().qualityIssues());
        if (content.isEmpty()) issues.add("NO_CONTENT_BLOCKS");
        if (sections.isEmpty()) issues.add("NO_SECTIONS");
        if (lowConfidence > Math.max(3, content.size() / 5)) issues.add("MANY_LOW_CONFIDENCE_BLOCKS");
        if (regionOnly > 0) issues.add("REGION_ONLY_VISUAL_ELEMENTS");
        return new PaperStructure.Quality(
                artifact.layoutConfidence(), average, lowConfidence, regionOnly,
                List.copyOf(issues));
    }

    private List<String> parseAuthors(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (!root.isArray()) return List.of(raw.trim());
            List<String> names = new ArrayList<>();
            for (JsonNode node : root) {
                String name = node.isObject() ? node.path("name").asText("") : node.asText("");
                if (!name.isBlank()) names.add(name.trim());
            }
            return List.copyOf(names);
        } catch (Exception ignored) {
            return List.of(raw.trim());
        }
    }

    private List<String> splitKeywords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : raw.split("[,;，；]")) {
            if (!value.isBlank()) values.add(value.trim());
        }
        return List.copyOf(values);
    }

    private int headingLevel(String heading) {
        Matcher numbered = NUMBERED_HEADING.matcher(heading == null ? "" : heading);
        if (numbered.matches()) return Math.min(6, numbered.group(1).split("\\.").length);
        if (LETTER_HEADING.matcher(heading == null ? "" : heading).matches()) return 2;
        if (ROMAN_HEADING.matcher(heading == null ? "" : heading).matches()) return 1;
        return 1;
    }

    private String elementContent(DocumentBlock block) {
        if (block.role() == DocumentBlockRole.FORMULA && block.latex() != null) return block.latex();
        if (block.role() == DocumentBlockRole.TABLE && block.tableText() != null) return block.tableText();
        return block.text();
    }

    private String elementLabel(DocumentBlock block) {
        String text = block.text().strip();
        Matcher figure = FIGURE_CAPTION.matcher(text);
        if (figure.matches()) return "Figure " + figure.group(1);
        Matcher table = TABLE_CAPTION.matcher(text);
        if (table.matches()) return "Table " + table.group(1);
        Matcher equation = Pattern.compile(".*(\\(\\d+[a-z]?\\))\\s*$").matcher(text);
        if (equation.matches()) return "Equation " + equation.group(1);
        return limit(text, 160);
    }

    private boolean rolesCanContinue(DocumentBlockRole previous, DocumentBlockRole current) {
        return previous == current || previous == DocumentBlockRole.BODY && current == DocumentBlockRole.BODY;
    }

    private boolean endsParagraph(String text) {
        return text == null || text.isBlank()
                || text.stripTrailing().matches(".*[.!?。！？:;：；]$");
    }

    private String cleanHeading(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String sectionId(int index) {
        return "section-" + String.format(Locale.ROOT, "%04d", index + 1);
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static final class SectionDraft {
        private final String id;
        private final String kind;
        private final String heading;
        private final int level;
        private final String parentId;
        private final List<String> path;
        private final List<String> blockIds = new ArrayList<>();
        private int pageStart = Integer.MAX_VALUE;
        private int pageEnd = 0;
        private int readingOrderStart = Integer.MAX_VALUE;
        private int readingOrderEnd = -1;

        private SectionDraft(String id, String kind, String heading, int level,
                             String parentId, List<String> path) {
            this.id = id;
            this.kind = kind;
            this.heading = heading;
            this.level = level;
            this.parentId = parentId;
            this.path = List.copyOf(path);
        }

        private void add(DocumentBlock block) {
            blockIds.add(block.id());
            pageStart = Math.min(pageStart, block.page());
            pageEnd = Math.max(pageEnd, block.page());
            readingOrderStart = Math.min(readingOrderStart, block.readingOrder());
            readingOrderEnd = Math.max(readingOrderEnd, block.readingOrder());
        }

        private PaperStructure.Section freeze() {
            return new PaperStructure.Section(
                    id, kind, heading, level, parentId, path,
                    pageStart == Integer.MAX_VALUE ? 0 : pageStart,
                    pageEnd,
                    readingOrderStart == Integer.MAX_VALUE ? -1 : readingOrderStart,
                    readingOrderEnd,
                    blockIds);
        }
    }
}
