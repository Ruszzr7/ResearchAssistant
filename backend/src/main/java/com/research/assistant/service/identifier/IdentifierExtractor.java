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
     * DOI 正则。
     * <p>
     * 只允许 DOI 结构内部的空白（例如 PDF 把 "10. 1109 /" 分成多段），
     * 不再把整篇 PDF 文本压成一行，否则 DOI 后面的正文会被误吞进 DOI。
     * 负向回顾确保不被数字或点号误匹配。
     */
    private static final Pattern DOI_PATTERN = Pattern.compile(
            "(?i)(?<![\\d.])10\\s*\\.\\s*\\d{4,}(?:\\s*\\.\\s*\\d+)*\\s*/\\s*[^\\s<>\"{}|\\\\^`\\[\\]]+");

    /** DOI 标签被 PDF 按字符拆开时的定位模式，例如 D\nO\nI: 10\n.1\n109/。 */
    private static final Pattern DOI_LABEL_PATTERN = Pattern.compile(
            "(?is)\\bD\\s*O\\s*I\\s*[:：]?\\s*");

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

        // 在原始文本中匹配，避免把 DOI 后面的正文拼接到 DOI 末尾。
        String doi = extractDoi(text);
        return new IdentifierResult(doi, null);
    }

    private String extractDoi(String text) {
        // IEEE 会议论文的 DOI 常在页脚被拆成多个短行，先从 DOI 标签后的连续短行拼接。
        Matcher labelMatcher = DOI_LABEL_PATTERN.matcher(text);
        boolean fragmentedLabel = false;
        while (labelMatcher.find()) {
            String[] lines = text.substring(labelMatcher.end()).split("\\R", 24);
            StringBuilder candidate = new StringBuilder();
            boolean firstFragment = true;
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                String line = lines[lineIndex];
                String compact = line.replaceAll("\\s+", "");
                if (compact.isBlank()) continue;
                if (isDoiTextTerminator(compact) || !looksLikeDoiFragment(compact)) break;
                if (firstFragment) {
                    fragmentedLabel = compact.startsWith("10") && !compact.contains("/");
                    firstFragment = false;
                }
                candidate.append(compact);
                String normalized = normalizeDoi(candidate.toString());
                if (!compact.endsWith(".") && isCompleteDoi(normalized)) {
                    if (hasDoiContinuation(lines, lineIndex)) continue;
                    return normalized;
                }
            }
        }

        // 非拆行 DOI 保留完整的标准匹配，兼容较短但合法的 DOI 后缀。
        if (!fragmentedLabel) {
            Matcher matcher = DOI_PATTERN.matcher(text);
            if (matcher.find()) return normalizeDoi(matcher.group());
        }
        return null;
    }

    private boolean hasDoiContinuation(String[] lines, int currentIndex) {
        for (int i = currentIndex + 1; i < lines.length; i++) {
            String next = lines[i].replaceAll("\\s+", "");
            if (next.isBlank()) continue;
            return looksLikeDoiFragment(next) && !isDoiTextTerminator(next);
        }
        return false;
    }

    private String normalizeDoi(String raw) {
        if (raw == null) return null;
        String doi = raw.replaceAll("\\s+", "")
                .replaceAll("[.,;:)\\\\\\]}+>\"']+$", "");
        if (doi.isBlank()) return null;
        Matcher matcher = DOI_PATTERN.matcher(doi);
        return matcher.find() && matcher.group().equals(doi) ? doi : null;
    }

    private boolean looksLikeDoiFragment(String value) {
        return value.length() <= 32
                && value.matches("(?i)[0-9a-z./:_()\\-]+");
    }

    private boolean isCompleteDoi(String doi) {
        if (doi == null) return false;
        int slash = doi.indexOf('/');
        if (slash < 0 || doi.length() - slash - 1 < 8) return false;
        String suffix = doi.substring(slash + 1);
        String lastSegment = suffix.substring(suffix.lastIndexOf('.') + 1);
        // 竖排 IEEE 页脚有时会把 DOI 后缀拆成“10.1109/VTC2023-F”这样的
        // 半截首行。仅凭长度会过早返回，必须等到后缀足够完整或出现正式的
        // 数字版本段（例如 .2023.10333373）。
        return (!suffix.contains(".")
                && suffix.length() >= 12
                && !suffix.contains("-"))
                || lastSegment.matches("\\d{6,}");
    }

    private boolean isDoiTextTerminator(String value) {
        return value.matches("(?i)(authorized|licensed|downloaded|restrictions|copyright|from|abstract|index|keywords).*")
                || value.length() > 48;
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
