package com.research.assistant.service.source;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一文献候选对象 —— 屏蔽不同学术来源的字段差异。
 */
public record LiteratureCandidate(
        String title,
        String authors,
        String year,
        String summary,
        String arxivId,
        String doi,
        String sourceUrl,
        String pdfUrl,
        String source,
        String externalId
) {

    /**
     * 转回前端/Skill 需要的 Map 格式。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("authors", authors);
        map.put("year", year);
        map.put("summary", summary);
        map.put("arxivId", arxivId);
        map.put("doi", doi);
        map.put("sourceUrl", sourceUrl);
        map.put("pdfUrl", pdfUrl);
        map.put("source", source);
        map.put("externalId", externalId);
        return map;
    }

    /**
     * 去重键优先级：DOI > arXiv ID > 标题+年份。
     */
    public String dedupeKey() {
        if (doi != null && !doi.isBlank()) {
            return "doi:" + doi.trim().toLowerCase();
        }
        if (arxivId != null && !arxivId.isBlank()) {
            return "arxiv:" + arxivId.trim().toLowerCase();
        }
        String key = String.valueOf(title).trim().toLowerCase();
        if (year != null && !year.isBlank()) {
            key += "|" + year.trim();
        }
        return "title:" + key;
    }

    /**
     * 是否包含可用于引用网络扩展的外部 ID。
     */
    public boolean hasExternalId() {
        return externalId != null && !externalId.isBlank();
    }

    /**
     * 将多个来源名称合并为逗号分隔字符串（用于展示）。
     */
    public static String mergeSources(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        List<String> parts = new java.util.ArrayList<>(List.of(a.split(",\\s*")));
        for (String p : b.split(",\\s*")) {
            if (!parts.contains(p)) parts.add(p);
        }
        return String.join(", ", parts);
    }
}
