package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes page-addressable MinerU JSON, GROBID TEI XML, or the documented
 * layout-json-v1 envelope into the internal artifact contract.
 */
@Component
public class ExternalLayoutPayloadNormalizer {

    static final String VERSION = "external-layout-v1";
    private static final Set<String> GROBID_BLOCK_TAGS = Set.of(
            "title", "author", "head", "p", "s", "formula", "figure", "table", "note", "biblstruct");

    private final ObjectMapper objectMapper;

    public ExternalLayoutPayloadNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PaperLayoutArtifact normalize(String provider,
                                         String payload,
                                         Long paperId,
                                         String documentHash) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("external layout payload is empty");
        }
        String normalizedProvider = normalizeProvider(provider, payload);
        return "GROBID".equals(normalizedProvider)
                ? normalizeGrobid(payload, paperId, documentHash)
                : normalizeJson(normalizedProvider, payload, paperId, documentHash);
    }

    private PaperLayoutArtifact normalizeJson(String provider,
                                              String payload,
                                              Long paperId,
                                              String documentHash) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Map<Integer, PageDimensions> dimensions = jsonPageDimensions(root);
            List<JsonBlockInput> inputs = jsonBlockInputs(root, dimensions);
            List<DocumentBlock> blocks = new ArrayList<>();
            int order = 0;
            for (JsonBlockInput input : inputs) {
                JsonNode node = input.node();
                int page = jsonPage(node, input.pageHint());
                NormalizedBoundingBox bbox = jsonBox(node, dimensions.get(page));
                if (page < 1 || bbox == null || bbox.width() <= 0 || bbox.height() <= 0) continue;
                DocumentBlockRole role = role(field(node, "type", "block_type", "category", "label"));
                String latex = field(node, "latex", "formula", "inline_latex");
                String tableText = field(node, "tableText", "table_text", "table_body", "html");
                String text = jsonText(node);
                if (text.isBlank()) {
                    text = role == DocumentBlockRole.FORMULA ? latex
                            : role == DocumentBlockRole.TABLE ? tableText : "";
                }
                if (text.isBlank() && role != DocumentBlockRole.FIGURE) continue;
                List<String> sectionPath = jsonStringList(node.get("sectionPath"));
                if (sectionPath.isEmpty()) sectionPath = jsonStringList(node.get("section_path"));
                double confidence = decimal(node, 0.78, "confidence", "score", "quality");
                blocks.add(new DocumentBlock(
                        "ext-p%d-b%04d".formatted(page, order),
                        page,
                        bbox,
                        role,
                        order++,
                        sectionPath,
                        text,
                        blankToNull(latex),
                        blankToNull(tableText),
                        confidence,
                        contentMode(role, latex, tableText),
                        null,
                        DocumentLayoutLane.infer(bbox)
                ));
            }
            return artifact(provider, paperId, documentHash, blocks,
                    integer(root, 0, "pageCount", "page_count", "pages_count"));
        } catch (Exception e) {
            throw new IllegalArgumentException("external layout JSON is invalid", e);
        }
    }

    private PaperLayoutArtifact normalizeGrobid(String payload,
                                                Long paperId,
                                                String documentHash) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(
                    new InputSource(new StringReader(payload)));
            Map<Integer, PageDimensions> dimensions = grobidPageDimensions(document);
            List<GrobidCandidate> candidates = grobidCandidates(document);
            inferMissingDimensions(dimensions, candidates);

            List<DocumentBlock> blocks = new ArrayList<>();
            int order = 0;
            for (GrobidCandidate candidate : candidates) {
                PageDimensions size = dimensions.get(candidate.box().page());
                NormalizedBoundingBox bbox = normalizeBox(candidate.box(), size);
                if (bbox == null) continue;
                String text = candidate.element().getTextContent().replaceAll("\\s+", " ").trim();
                DocumentBlockRole role = grobidRole(candidate.element());
                String latex = role == DocumentBlockRole.FORMULA ? text : null;
                String tableText = role == DocumentBlockRole.TABLE ? text : null;
                if (text.isBlank() && role != DocumentBlockRole.FIGURE) continue;
                blocks.add(new DocumentBlock(
                        "ext-p%d-b%04d".formatted(candidate.box().page(), order),
                        candidate.box().page(), bbox, role, order++, List.of(), text,
                        latex, tableText, size.inferred() ? 0.64 : 0.82,
                        contentMode(role, latex, tableText), null,
                        DocumentLayoutLane.infer(bbox)));
            }
            return artifact("GROBID", paperId, documentHash, blocks,
                    dimensions.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        } catch (Exception e) {
            throw new IllegalArgumentException("GROBID TEI is invalid or has no usable coordinates", e);
        }
    }

    private PaperLayoutArtifact artifact(String provider,
                                         Long paperId,
                                         String documentHash,
                                         List<DocumentBlock> source,
                                         int declaredPageCount) {
        List<DocumentBlock> blocks = source.stream()
                .sorted(Comparator.comparingInt(DocumentBlock::page)
                        .thenComparingInt(DocumentBlock::readingOrder))
                .toList();
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("external parser returned no page-addressable blocks");
        }
        int pageCount = Math.max(declaredPageCount,
                blocks.stream().mapToInt(DocumentBlock::page).max().orElse(0));
        double confidence = blocks.stream().mapToDouble(DocumentBlock::confidence).average().orElse(0);
        String parser = provider.toLowerCase(Locale.ROOT) + "-" + VERSION;
        return new PaperLayoutArtifact(
                paperId, documentHash, parser, confidence, Instant.now(), pageCount, blocks,
                LayoutArtifactProvenance.direct(parser, confidence));
    }

    private Map<Integer, PageDimensions> jsonPageDimensions(JsonNode root) {
        Map<Integer, PageDimensions> result = new HashMap<>();
        JsonNode pages = root.isObject() ? first(root, "pages", "page_info", "pageInfos") : null;
        if (pages != null && pages.isArray()) {
            int index = 0;
            for (JsonNode page : pages) {
                int number = jsonPage(page, index + 1);
                PageDimensions size = dimensions(page);
                if (size != null) result.put(number, size);
                index++;
            }
        }
        JsonNode pdfInfo = root.isObject() ? root.get("pdf_info") : null;
        if (pdfInfo != null && pdfInfo.isArray()) {
            int index = 0;
            for (JsonNode page : pdfInfo) {
                int number = jsonPage(page, index + 1);
                PageDimensions size = dimensions(page);
                if (size != null) result.put(number, size);
                index++;
            }
        }
        return result;
    }

    private List<JsonBlockInput> jsonBlockInputs(JsonNode root,
                                                 Map<Integer, PageDimensions> dimensions) {
        List<JsonBlockInput> result = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(node -> result.add(new JsonBlockInput(node, 0)));
            return result;
        }
        appendJsonArray(result, first(root, "blocks", "content_list", "content"), 0);
        JsonNode pdfInfo = root.get("pdf_info");
        if (pdfInfo != null && pdfInfo.isArray()) {
            int index = 0;
            for (JsonNode page : pdfInfo) {
                int pageNumber = jsonPage(page, index + 1);
                appendJsonArray(result, first(page, "para_blocks", "blocks", "content_list"), pageNumber);
                index++;
            }
        }
        return result;
    }

    private void appendJsonArray(List<JsonBlockInput> target, JsonNode array, int pageHint) {
        if (array == null || !array.isArray()) return;
        for (JsonNode node : array) {
            if (hasBox(node)) {
                target.add(new JsonBlockInput(node, pageHint));
            } else {
                appendJsonArray(target, first(node, "blocks", "children", "lines"), pageHint);
            }
        }
    }

    private NormalizedBoundingBox jsonBox(JsonNode node, PageDimensions dimensions) {
        JsonNode box = first(node, "bbox", "box", "bounding_box");
        if (box == null) return null;
        double x;
        double y;
        double width;
        double height;
        if (box.isArray() && box.size() >= 4) {
            x = box.get(0).asDouble();
            y = box.get(1).asDouble();
            String mode = field(node, "bboxMode", "bbox_mode");
            if ("XYWH".equalsIgnoreCase(mode)) {
                width = box.get(2).asDouble();
                height = box.get(3).asDouble();
            } else {
                width = box.get(2).asDouble() - x;
                height = box.get(3).asDouble() - y;
            }
        } else if (box.isObject()) {
            x = decimal(box, 0, "x", "left");
            y = decimal(box, 0, "y", "top");
            width = decimal(box, 0, "width", "w");
            height = decimal(box, 0, "height", "h");
            if (width <= 0) width = decimal(box, x, "right", "x2") - x;
            if (height <= 0) height = decimal(box, y, "bottom", "y2") - y;
        } else {
            return null;
        }
        if (width <= 0 || height <= 0) return null;
        boolean normalized = x <= 1.0001 && y <= 1.0001
                && width <= 1.0001 && height <= 1.0001;
        if (normalized) return new NormalizedBoundingBox(x, y, width, height);
        if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) return null;
        return new NormalizedBoundingBox(
                x / dimensions.width(), y / dimensions.height(),
                width / dimensions.width(), height / dimensions.height());
    }

    private Map<Integer, PageDimensions> grobidPageDimensions(Document document) {
        Map<Integer, PageDimensions> result = new HashMap<>();
        NodeList elements = document.getElementsByTagNameNS("*", "surface");
        for (int index = 0; index < elements.getLength(); index++) {
            Element surface = (Element) elements.item(index);
            int page = integerAttribute(surface, index + 1, "n", "page");
            double left = decimalAttribute(surface, 0, "ulx", "x");
            double top = decimalAttribute(surface, 0, "uly", "y");
            double right = decimalAttribute(surface, 0, "lrx", "width");
            double bottom = decimalAttribute(surface, 0, "lry", "height");
            double width = right > left ? right - left : right;
            double height = bottom > top ? bottom - top : bottom;
            if (width > 0 && height > 0) result.put(page, new PageDimensions(width, height, false));
        }
        return result;
    }

    private List<GrobidCandidate> grobidCandidates(Document document) {
        List<GrobidCandidate> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element element = (Element) nodes.item(index);
            String tag = localName(element);
            String coords = element.getAttribute("coords");
            if (!GROBID_BLOCK_TAGS.contains(tag) || coords.isBlank() || hasCoordinateBlockAncestor(element)) {
                continue;
            }
            CoordinateBox box = parseCoordinateBox(coords);
            if (box != null) result.add(new GrobidCandidate(element, box));
        }
        return result;
    }

    private void inferMissingDimensions(Map<Integer, PageDimensions> dimensions,
                                        List<GrobidCandidate> candidates) {
        Map<Integer, double[]> maxima = new HashMap<>();
        for (GrobidCandidate candidate : candidates) {
            CoordinateBox box = candidate.box();
            double[] value = maxima.computeIfAbsent(box.page(), ignored -> new double[] {0, 0});
            value[0] = Math.max(value[0], box.x() + box.width());
            value[1] = Math.max(value[1], box.y() + box.height());
        }
        maxima.forEach((page, value) -> dimensions.computeIfAbsent(page, ignored ->
                new PageDimensions(value[0] * 1.03, value[1] * 1.03, true)));
    }

    private NormalizedBoundingBox normalizeBox(CoordinateBox box, PageDimensions dimensions) {
        if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0
                || box.width() <= 0 || box.height() <= 0) return null;
        return new NormalizedBoundingBox(
                box.x() / dimensions.width(), box.y() / dimensions.height(),
                box.width() / dimensions.width(), box.height() / dimensions.height());
    }

    private CoordinateBox parseCoordinateBox(String value) {
        String first = value.split(";")[0].trim();
        String[] parts = first.split(",");
        if (parts.length < 5) return null;
        try {
            return new CoordinateBox(
                    Integer.parseInt(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()),
                    Double.parseDouble(parts[4].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DocumentBlockRole grobidRole(Element element) {
        String tag = localName(element);
        if (hasAncestor(element, "listbibl")) return DocumentBlockRole.REFERENCE;
        if (hasAncestor(element, "abstract")) return DocumentBlockRole.ABSTRACT;
        if (hasAncestor(element, "teiheader")) {
            return "author".equals(tag) ? DocumentBlockRole.AUTHOR : DocumentBlockRole.TITLE;
        }
        return switch (tag) {
            case "title" -> DocumentBlockRole.TITLE;
            case "author" -> DocumentBlockRole.AUTHOR;
            case "head" -> DocumentBlockRole.HEADING;
            case "formula" -> DocumentBlockRole.FORMULA;
            case "figure" -> DocumentBlockRole.FIGURE;
            case "table" -> DocumentBlockRole.TABLE;
            case "note" -> DocumentBlockRole.MARGIN_METADATA;
            case "biblstruct" -> DocumentBlockRole.REFERENCE;
            default -> DocumentBlockRole.BODY;
        };
    }

    private DocumentBlockRole role(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.contains("page_header") || value.equals("header")) return DocumentBlockRole.HEADER;
        if (value.contains("page_footer") || value.equals("footer")) return DocumentBlockRole.FOOTER;
        if (value.contains("footnote") || value.contains("margin")) return DocumentBlockRole.MARGIN_METADATA;
        if (value.contains("title")) return DocumentBlockRole.TITLE;
        if (value.contains("author")) return DocumentBlockRole.AUTHOR;
        if (value.contains("abstract")) return DocumentBlockRole.ABSTRACT;
        if (value.contains("heading") || value.equals("head")) return DocumentBlockRole.HEADING;
        if (value.contains("formula") || value.contains("equation")) return DocumentBlockRole.FORMULA;
        if (value.contains("table_caption") || value.contains("image_caption") || value.contains("caption")) {
            return DocumentBlockRole.CAPTION;
        }
        if (value.contains("table")) return DocumentBlockRole.TABLE;
        if (value.contains("image") || value.contains("figure")) return DocumentBlockRole.FIGURE;
        if (value.contains("reference") || value.contains("bibli")) return DocumentBlockRole.REFERENCE;
        return DocumentBlockRole.BODY;
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

    private String normalizeProvider(String provider, String payload) {
        String value = provider == null ? "AUTO" : provider.trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(value)) return payload.stripLeading().startsWith("<") ? "GROBID" : "MINERU";
        if (!Set.of("GROBID", "MINERU").contains(value)) {
            throw new IllegalArgumentException("unsupported external layout provider");
        }
        return value;
    }

    private int jsonPage(JsonNode node, int fallback) {
        if (node == null) return fallback;
        if (node.has("page_idx")) return node.get("page_idx").asInt() + 1;
        if (node.has("page_index")) return node.get("page_index").asInt() + 1;
        int page = integer(node, fallback, "page", "page_number", "pageNum", "n");
        return page == 0 ? 1 : page;
    }

    private PageDimensions dimensions(JsonNode node) {
        if (node == null) return null;
        double width = decimal(node, 0, "width", "page_width");
        double height = decimal(node, 0, "height", "page_height");
        JsonNode size = first(node, "page_size", "size");
        if (size != null && size.isArray() && size.size() >= 2) {
            width = size.get(0).asDouble(width);
            height = size.get(1).asDouble(height);
        }
        return width > 0 && height > 0 ? new PageDimensions(width, height, false) : null;
    }

    private String jsonText(JsonNode node) {
        String direct = field(node, "text", "content", "md", "markdown");
        if (!direct.isBlank()) return direct.replaceAll("\\s+", " ").trim();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectText(first(node, "lines", "spans", "children", "blocks"), values);
        return String.join(" ", values).replaceAll("\\s+", " ").trim();
    }

    private void collectText(JsonNode node, Set<String> values) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectText(child, values));
            return;
        }
        if (!node.isObject()) return;
        String value = field(node, "text", "content");
        if (!value.isBlank()) values.add(value.trim());
        collectText(first(node, "lines", "spans", "children", "blocks"), values);
    }

    private boolean hasBox(JsonNode node) {
        return node != null && first(node, "bbox", "box", "bounding_box") != null;
    }

    private JsonNode first(JsonNode node, String... names) {
        if (node == null || !node.isObject()) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) return value;
        }
        return null;
    }

    private String field(JsonNode node, String... names) {
        JsonNode value = first(node, names);
        return value == null || value.isContainerNode() ? "" : value.asText("");
    }

    private int integer(JsonNode node, int fallback, String... names) {
        JsonNode value = first(node, names);
        return value == null ? fallback : value.asInt(fallback);
    }

    private double decimal(JsonNode node, double fallback, String... names) {
        JsonNode value = first(node, names);
        return value == null ? fallback : value.asDouble(fallback);
    }

    private List<String> jsonStringList(JsonNode node) {
        if (node == null) return List.of();
        if (node.isTextual()) return node.asText().isBlank() ? List.of() : List.of(node.asText().trim());
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().trim());
        });
        return List.copyOf(result);
    }

    private boolean hasCoordinateBlockAncestor(Element element) {
        org.w3c.dom.Node parent = element.getParentNode();
        while (parent instanceof Element ancestor) {
            if (GROBID_BLOCK_TAGS.contains(localName(ancestor)) && ancestor.hasAttribute("coords")) return true;
            parent = parent.getParentNode();
        }
        return false;
    }

    private boolean hasAncestor(Element element, String expected) {
        org.w3c.dom.Node parent = element;
        while (parent instanceof Element ancestor) {
            if (expected.equals(localName(ancestor))) return true;
            parent = parent.getParentNode();
        }
        return false;
    }

    private String localName(Element element) {
        String name = element.getLocalName();
        return (name == null ? element.getTagName() : name).toLowerCase(Locale.ROOT);
    }

    private int integerAttribute(Element element, int fallback, String... names) {
        for (String name : names) {
            try {
                if (element.hasAttribute(name)) return Integer.parseInt(element.getAttribute(name));
            } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private double decimalAttribute(Element element, double fallback, String... names) {
        for (String name : names) {
            try {
                if (element.hasAttribute(name)) return Double.parseDouble(element.getAttribute(name));
            } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record JsonBlockInput(JsonNode node, int pageHint) { }
    private record PageDimensions(double width, double height, boolean inferred) { }
    private record CoordinateBox(int page, double x, double y, double width, double height) { }
    private record GrobidCandidate(Element element, CoordinateBox box) { }
}
