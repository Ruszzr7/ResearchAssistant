package com.research.assistant.service.metadata;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 对 PDF 做轻量的论文结构审查。
 *
 * <p>这不是论文分类模型，只拦截明显由图片、流程图或零散标签组成的非论文 PDF。
 * 无法从文本可靠判断的文件返回 {@link Status#UNCERTAIN}，避免误伤扫描版或特殊排版论文。</p>
 */
@Component
public final class PaperDocumentReviewer {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(?:10\\.\\d{4,9}/[-._;()/:a-z0-9]+|arxiv\\s*:?\\s*\\d{4}\\.\\d{4,5}(?:v\\d+)?)");
    private static final Pattern ABSTRACT = Pattern.compile("(?im)(?:^|\\R)\\s*(?:abstract|摘要)\\b");
    private static final Pattern SECTION = Pattern.compile(
            "(?im)(?:^|\\R)\\s*(?:\\d+(?:\\.\\d+)*\\s+)?"
                    + "(?:introduction|background|preliminaries|method(?:ology)?|approach|system model|"
                    + "proposed method|experiments?|results?|discussion|conclusion|references|"
                    + "引言|背景|预备知识|方法|实验|结果|讨论|结论|参考文献)\\s*[:：.]?\\s*$");

    private static final int MIN_PROSE_CHARS = 600;
    private static final int MIN_SHORT_DOCUMENT_PROSE_CHARS = 280;

    /** 审查结论。PAPER 允许直接导入，NOT_PAPER 必须拦截，UNCERTAIN 交给用户确认。 */
    public enum Status {
        PAPER,
        NOT_PAPER,
        UNCERTAIN
    }

    public Review review(String identityText, String metadataText) {
        String identity = normalize(identityText);
        String text = normalize(metadataText);
        String firstPage = identity.isBlank() ? text : identity;

        boolean hasIdentifier = IDENTIFIER.matcher(firstPage).find();
        boolean hasAbstract = ABSTRACT.matcher(text).find();
        boolean hasAuthor = hasAuthorSignal(text);
        int sectionCount = countSections(text);
        int proseChars = countProseCharacters(text);
        int longLines = countLongLines(text);
        boolean fragmented = isFragmented(text, proseChars, longLines);

        // DOI/arXiv 与论文首页结构是最可靠的本地信号，已有元数据识别链路也会继续校验标题。
        if (hasIdentifier) {
            return new Review(Status.PAPER, "检测到论文标识符，允许导入");
        }

        // 常见短论文、中文论文或没有 DOI 的预印本，只要有摘要/作者和一定正文即可通过。
        if (hasAbstract && (hasAuthor || sectionCount >= 1)
                && proseChars >= MIN_SHORT_DOCUMENT_PROSE_CHARS) {
            return new Review(Status.PAPER, "检测到摘要、作者或论文正文结构，允许导入");
        }
        if (sectionCount >= 2 && proseChars >= MIN_PROSE_CHARS && !fragmented) {
            return new Review(Status.PAPER, "检测到多个论文章节和连续正文，允许导入");
        }
        // Older papers often start directly with Introduction and use initials in the byline.
        // One clear author line plus a section and substantial prose is sufficient evidence.
        if (hasAuthor && sectionCount >= 1 && proseChars >= MIN_PROSE_CHARS && !fragmented) {
            return new Review(Status.PAPER, "检测到作者、论文章节和连续正文，允许导入");
        }

        // 只拦截证据非常明确的非论文：没有论文身份/结构，且文本主要是短标签或极少内容。
        // 文本为空的扫描版不在这里拒绝，而是返回 UNCERTAIN 交给用户确认。
        if (!text.isBlank() && !hasIdentifier && !hasAbstract && !hasAuthor
                && sectionCount == 0
                && (proseChars < MIN_SHORT_DOCUMENT_PROSE_CHARS || fragmented)) {
            return new Review(Status.NOT_PAPER, "导入文件不是有效论文");
        }

        return new Review(Status.UNCERTAIN,
                "无法仅根据 PDF 文本确认是否为论文，请核对文件内容后再导入");
    }

    private boolean hasAuthorSignal(String text) {
        String[] lines = text.split("\\R");
        int inspected = Math.min(lines.length, 36);
        for (int index = 0; index < inspected; index++) {
            String line = lines[index].trim();
            if (line.isBlank() || line.length() < 4 || line.length() > 240) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("abstract") || lower.contains("doi") || lower.contains("arxiv")) continue;
            if (line.contains("@") || lower.contains("corresponding author")
                    || lower.contains("member, ieee") || lower.contains("university")
                    || lower.contains("institute") || lower.contains("学院")
                    || lower.contains("大学")) {
                return true;
            }
            // English bylines may contain initials (for example "C. E. Shannon") or a leading "by".
            if (line.matches("(?i)^(?:by\\s+)?(?:[A-Z](?:[A-Za-z'\\-]+|\\.)\\s+){1,7}"
                    + "[A-Z][A-Za-z'\\-]+(?:\\s*[,*†‡].*)?$")) {
                return true;
            }
        }
        return false;
    }

    private int countSections(String text) {
        int count = 0;
        var matcher = SECTION.matcher(text);
        while (matcher.find()) count++;
        return count;
    }

    private int countProseCharacters(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetter(character) || Character.isDigit(character)) count++;
        }
        return count;
    }

    private int countLongLines(String text) {
        int count = 0;
        for (String line : text.split("\\R")) {
            if (line.trim().length() >= 40) count++;
        }
        return count;
    }

    private boolean isFragmented(String text, int proseChars, int longLines) {
        if (text.isBlank()) return false;
        String[] tokens = text.trim().split("\\s+");
        int shortTokens = 0;
        for (String token : tokens) {
            if (token.replaceAll("[^\\p{L}\\p{N}]", "").length() <= 3) shortTokens++;
        }
        return proseChars < MIN_PROSE_CHARS
                && longLines <= 2
                && tokens.length >= 8
                && shortTokens * 1.0 / tokens.length >= 0.55;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace('\u0000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    public record Review(Status status, String message) {
    }
}
