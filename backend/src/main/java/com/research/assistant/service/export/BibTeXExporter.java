package com.research.assistant.service.export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 将论文元数据导出为 BibTeX 条目。
 */
@Component
public class BibTeXExporter {

    private static final Logger log = LoggerFactory.getLogger(BibTeXExporter.class);

    private final ObjectMapper objectMapper;

    public BibTeXExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String export(Paper paper) {
        if (paper == null) return "";
        String key = citationKey(paper);
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(entryType(paper)).append("{").append(key).append(",\n");
        appendField(sb, "title", paper.getTitle());
        appendField(sb, "author", formatAuthors(paper.getAuthors()));
        if (paper.getYear() != null) {
            appendField(sb, "year", String.valueOf(paper.getYear()));
        }
        appendField(sb, "journal", journal(paper));
        appendField(sb, "booktitle", booktitle(paper));
        appendField(sb, "doi", paper.getDoi());
        appendField(sb, "eprint", paper.getArxivId());
        if (paper.getArxivId() != null && !paper.getArxivId().isBlank()) {
            appendField(sb, "archivePrefix", "arXiv");
        }
        appendField(sb, "url", paper.getSourceUrl());
        if (sb.charAt(sb.length() - 2) == ',') {
            sb.setLength(sb.length() - 2);
            sb.append("\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    public String exportBatch(List<Paper> papers) {
        StringBuilder sb = new StringBuilder();
        for (Paper paper : papers) {
            sb.append(export(paper));
        }
        return sb.toString();
    }

    private String entryType(Paper paper) {
        String source = paper.getSource() == null ? "" : paper.getSource().toLowerCase();
        if (source.contains("conference") || source.contains("proceedings") || source.contains("symposium") || source.contains("workshop")) {
            return "inproceedings";
        }
        if (!source.isBlank() || (paper.getArxivId() != null && !paper.getArxivId().isBlank())) {
            return "article";
        }
        return "misc";
    }

    private String journal(Paper paper) {
        String source = paper.getSource();
        if (source == null) return null;
        String lower = source.toLowerCase();
        if (lower.contains("conference") || lower.contains("proceedings") || lower.contains("symposium") || lower.contains("workshop")) {
            return null;
        }
        return source;
    }

    private String booktitle(Paper paper) {
        String source = paper.getSource();
        if (source == null) return null;
        String lower = source.toLowerCase();
        if (lower.contains("conference") || lower.contains("proceedings") || lower.contains("symposium") || lower.contains("workshop")) {
            return source;
        }
        return null;
    }

    private void appendField(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("  ").append(name).append(" = ").append(brace(value)).append(",\n");
    }

    private String brace(String value) {
        return "{" + value.replace("{", "\\{").replace("}", "\\}").trim() + "}";
    }

    private String citationKey(Paper paper) {
        String firstAuthor = firstAuthorLastName(paper.getAuthors());
        String year = paper.getYear() == null ? "0000" : String.valueOf(paper.getYear());
        String titleWord = titleFirstWord(paper.getTitle());
        return (firstAuthor + year + titleWord).replaceAll("[^a-zA-Z0-9]", "");
    }

    private String firstAuthorLastName(String authorsJson) {
        List<Map<String, Object>> authors = parseAuthors(authorsJson);
        if (authors.isEmpty()) return "unknown";
        String name = String.valueOf(authors.get(0).getOrDefault("name", ""));
        String[] parts = name.trim().split("\\s+");
        return parts.length > 0 ? parts[parts.length - 1] : "unknown";
    }

    private String formatAuthors(String authorsJson) {
        List<Map<String, Object>> authors = parseAuthors(authorsJson);
        if (authors.isEmpty()) return "";
        return authors.stream()
                .map(a -> String.valueOf(a.getOrDefault("name", "")).trim())
                .filter(n -> !n.isBlank())
                .reduce((a, b) -> a + " and " + b)
                .orElse("");
    }

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "a", "an", "the", "on", "in", "of", "for", "to", "and", "with");

    private String titleFirstWord(String title) {
        if (title == null) return "";
        String[] words = title.split("\\s+");
        for (String w : words) {
            String clean = w.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (!clean.isBlank() && !STOP_WORDS.contains(clean)) return w.replaceAll("[^a-zA-Z0-9]", "");
        }
        return "";
    }

    private List<Map<String, Object>> parseAuthors(String authorsJson) {
        if (authorsJson == null || authorsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(authorsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.debug("作者 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
