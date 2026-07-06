package com.research.assistant.service.identifier;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从论文文本中扫描学术标识符：DOI 与 arXiv ID。
 * <p>
 * 参考 Zotero 的元数据提取思路：先扫描 PDF 前几页文本，找到 DOI 或 arXiv ID 后再查外部 API。
 */
@Component
public class IdentifierExtractor {

    /**
     * DOI 正则（用于已去除空白的紧凑文本）。
     * <p>
     * 负向回顾确保不被数字或点号误匹配。
     */
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "(?i)(?<![\\d.])10\\.\\d{4,}(?:\\.\\d+)*/[^\\s<>\"{}|\\\\^`\\[\\]]+");

    /**
     * arXiv ID 正则。
     * <p>
     * 必须带有 arXiv 前缀或 arxiv.org 路径，避免把 DOI 中的数字段误识别为 arXiv ID。
     */
    private static final Pattern ARXIV_PATTERN = Pattern.compile(
            "(?i)(?:arxiv\\.org/(?:abs|pdf)/|ar[xX]iv:?\\s*)" +
            "((?:\\d{4}\\.\\d{4,5}(?:v\\d+)?)|(?:[a-z-]+(?:\\.[A-Z]{2})?/\\d{7}))");

    /**
     * 从文本中提取标识符。优先返回 arXiv ID（元数据更全），其次 DOI。
     *
     * @param text PDF 文本
     * @return 识别结果，不会为 null
     */
    public IdentifierResult extract(String text) {
        if (text == null || text.isBlank()) {
            return new IdentifierResult(null, null);
        }

        String arxivId = extractArxivId(text);
        if (arxivId != null) {
            return new IdentifierResult(null, arxivId);
        }

        // PDF 文本提取可能在 DOI 中插入空格，先移除所有空白再匹配
        String compact = text.replaceAll("\\s+", "");
        String doi = extractDoi(compact);
        return new IdentifierResult(doi, null);
    }

    private String extractDoi(String text) {
        Matcher matcher = DOI_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String doi = matcher.group();
        // 去除内部可能存在的换行/空格
        doi = doi.replaceAll("\\s+", "");
        // 去除末尾常见标点
        doi = doi.replaceAll("[.,;:)\\\\\\]}+>\"']+$", "");
        return doi.isBlank() ? null : doi;
    }

    private String extractArxivId(String text) {
        Matcher matcher = ARXIV_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return normalizeArxivId(matcher.group(1));
    }

    private String normalizeArxivId(String id) {
        if (id == null) {
            return null;
        }
        return id.replaceAll("v\\d+$", "").trim();
    }
}
