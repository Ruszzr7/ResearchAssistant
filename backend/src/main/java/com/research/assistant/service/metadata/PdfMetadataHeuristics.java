package com.research.assistant.service.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 PDF 前几页的排版文本中提取标题和摘要的轻量级兜底逻辑。
 *
 * <p>Crossref/arXiv 返回的元数据优先；当外部记录没有摘要或 PDF 没有 DOI 时，
 * 使用论文常见的「出版信息 → 标题 → 作者 → Abstract」结构补齐字段。该逻辑不调用模型，
 * 只用于导入预览，避免一次导入触发额外的 LLM 请求。</p>
 */
public final class PdfMetadataHeuristics {

    public static final int MAX_ABSTRACT_LENGTH = 3000;

    private static final Pattern ABSTRACT_HEADER = Pattern.compile(
            "(?im)^\\s*(?:abstract|摘要)\\s*(?:[-–—:：.]\\s*)?");
    private static final Pattern ABSTRACT_END = Pattern.compile(
            "(?im)(?:(?:^|\\R)\\s*(?:index\\s+terms?|keywords?|key\\s+words?|introduction)"
                    + "|(?:^|\\R|\\s)(?:[ivxlcdm]+|\\d+)\\s*\\.?\\s*introduction)"
                    + "\\s*[:：–—-]?\\s*");
    private static final Pattern KEYWORDS_HEADER = Pattern.compile(
            "(?im)^\\s*(?:index\\s+terms?|keywords?|key\\s+words?|关键词)\\s*"
                    + "(?:[-–—:：.]\\s*)?");
    private static final Pattern PUBLICATION_SIGNAL = Pattern.compile(
            "(?i)\\b(?:proceedings?|conference|symposium|workshop|journal|transactions|"
                    + "letters|magazine|review|annual meeting|international conference|"
                    + "ieee/cvf|cvpr|iccv|eccv|icml|neurips|iclr|acl|emnlp|naacl|kdd|"
                    + "sigir|chi|ijcai|icra|iros|icassp|infocom|globecom|interspeech|"
                    + "usenix|pmlr)\\b");
    private static final Pattern PUBLICATION_PREFIX = Pattern.compile(
            "(?i)^.*?\\b(?:publication|published)\\s+in\\s*:?\\s*");
    private static final Pattern LEADING_YEAR = Pattern.compile("^20\\d{2}\\s+");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(?:19\\d{2}|20\\d{2})\\b");
    private static final Pattern ISSUE_SUFFIX = Pattern.compile(
            "(?i)\\s*,?\\s*(?:vol(?:ume)?\\.?|no\\.?|issue|pp?\\.?|pages?)\\s+.*$");
    private static final Pattern MONTH_OR_YEAR_SUFFIX = Pattern.compile(
            "(?i)\\s*,?\\s*(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|"
                    + "jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|"
                    + "nov(?:ember)?|dec(?:ember)?|20\\d{2})(?:\\s*,.*|\\s*)$");

    public Metadata extract(String text) {
        if (text == null || text.isBlank()) {
            return new Metadata(null, null, null, null, null, null);
        }

        Matcher abstractMatcher = ABSTRACT_HEADER.matcher(text);
        String source = extractSource(text);
        Integer year = extractYear(text);
        if (!abstractMatcher.find()) {
            String title = extractTitle(text);
            return new Metadata(title, extractAuthors(text, title), year, null, source, extractKeywords(text));
        }

        String prefix = text.substring(0, abstractMatcher.start());
        String title = extractTitle(prefix);
        String abstractText = extractAbstract(text, abstractMatcher.end());
        return new Metadata(title, extractAuthors(prefix, title), year, abstractText, source, extractKeywords(text));
    }

    /**
     * 从前几页的页眉/标题区提取期刊或会议名称。
     *
     * <p>会议论文经常没有 Crossref 的 container-title，出版信息却会出现在首页页眉，
     * 因此这里保留一个不依赖外部 API 的轻量级兜底。</p>
     */
    private String extractSource(String text) {
        Matcher abstractMatcher = ABSTRACT_HEADER.matcher(text);
        String header = abstractMatcher.find() ? text.substring(0, abstractMatcher.start()) : text;
        String headerCandidate = findSourceCandidate(header);
        if (headerCandidate != null && isStrongSourceLine(headerCandidate)) {
            return headerCandidate;
        }
        // 会议名称常位于首页页脚，扫描摘要之后的文本，但优先选择强出版信号行，
        // 避免把正文或参考文献中的普通 conference 单词误当来源。
        String wholeCandidate = findSourceCandidate(text);
        if (wholeCandidate != null
                && (isStrongSourceLine(wholeCandidate) || !wholeCandidate.equals(headerCandidate))) {
            return wholeCandidate;
        }
        return headerCandidate;
    }

    private String findSourceCandidate(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.split("\\R");
        // 会议出版信息既可能在首页页眉，也可能在首页页脚或双栏正文后的页脚区域。
        // 扫描前 240 行仍只覆盖元数据区域，避免把全文参考文献当成来源。
        int limit = Math.min(lines.length, 240);
        String fallback = null;
        String strong = null;

        for (int i = 0; i < limit; i++) {
            String line = clean(lines[i]);
            String candidate = publicationCandidate(line);
            if (isSourceCandidate(candidate)) {
                if (isStrongSourceLine(line)) strong = longerCandidate(strong, candidate);
                if (fallback == null) fallback = candidate;
            }

            // 左页边距中的会议名经常被 PDF 文本流拆成 3～4 行，例如：
            // “2024 IEEE / International Conference on Communications / in China”。
            // 仅合并两行会得到残缺出处，因此在确认下一行仍像出处续行时继续合并。
            if (isLikelyPublicationStart(line)) {
                String joined = line;
                for (int offset = 1; offset <= 3 && i + offset < limit; offset++) {
                    String next = clean(lines[i + offset]);
                    if (next.isBlank() || !isLikelyPublicationContinuation(joined, next)) break;
                    joined = joined + " " + next;
                    String wrappedCandidate = publicationCandidate(joined);
                    if (isSourceCandidate(wrappedCandidate) && isStrongSourceLine(joined)) {
                        strong = longerCandidate(strong, wrappedCandidate);
                    }
                }
            }

            // IEEE 会议的左页边距信息在 PDF 内容流中经常按“年份 → IEEE → 会议名”
            // 拆成单词行。按相邻行重组，直到 ISBN/DOI 分隔符为止。
            if (line.matches("20\\d{2}") && i + 1 < limit) {
                String joined = joinPublicationBlock(lines, i, limit);
                String wrappedCandidate = publicationCandidate(joined);
                if (isSourceCandidate(wrappedCandidate) && isStrongSourceLine(joined)) {
                    strong = longerCandidate(strong, wrappedCandidate);
                    if (fallback == null) fallback = wrappedCandidate;
                }
            }
        }
        return strong != null ? strong : fallback;
    }

    private String joinPublicationBlock(String[] lines, int start, int limit) {
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < Math.min(limit, start + 18); i++) {
            String line = clean(lines[i]);
            if (line.isBlank()) break;
            if (line.matches("(?i).*(?:doi\\s*:|authorized licensed|downloaded on|restrictions apply).*")) {
                break;
            }
            if (joined.length() > 0) joined.append(' ');
            joined.append(line);
            if ("|".equals(line)) break;
        }
        return joined.toString();
    }

    private String longerCandidate(String current, String candidate) {
        if (current == null || current.isBlank()) return candidate;
        return candidate.length() > current.length() ? candidate : current;
    }

    private boolean isLikelyPublicationStart(String line) {
        return line != null && !line.isBlank()
                && line.matches("(?i).*\\b(?:ieee|international|national|annual|world|proceedings|"
                + "conference|symposium|workshop)\\b.*");
    }

    private boolean isLikelyPublicationContinuation(String joined, String next) {
        String lowerNext = next.toLowerCase(java.util.Locale.ROOT);
        if (joined.matches("(?i).*\\b(?:on|of|the|and|for|in|with)\\s*$")
                || joined.endsWith("/") || joined.endsWith("&")) {
            return true;
        }
        return lowerNext.matches("^(?:on|of|the|and|for|in|with|international|national|annual|world|"
                + "conference|symposium|workshop|communications|computer|vision|learning|systems)\\b.*");
    }

    private boolean isStrongSourceLine(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        boolean publicationWord = lower.matches(".*\\b(?:journal|transactions|letters|magazine|conference|"
                + "proceedings|symposium|workshop)\\b.*");
        boolean genericConference = lower.matches(".*\\b(?:international|national|annual|" 
                + "world)\\s+conference\\b.*")
                || lower.matches(".*\\bconference\\s+on\\b.*")
                || lower.matches(".*\\bproceedings\\s+of\\b.*");
        return publicationWord && (lower.contains("ieee")
                || lower.contains("proceedings")
                || genericConference
                || lower.matches(".*\\b(?:iccc|infocom|globecom|icassp|interspeech|cvpr|icml|neurips)\\b.*"));
    }

    /** 从出版信息行提取年份，优先于正文中的年份或页码。 */
    private Integer extractYear(String text) {
        String[] lines = text.split("\\R");
        int limit = Math.min(lines.length, 240);

        for (int i = 0; i < limit; i++) {
            String line = clean(lines[i]);
            String candidate = publicationCandidate(line);
            if (isSourceCandidate(candidate) || isStrongSourceLine(line)) {
                Integer year = firstYear(line);
                if (year != null) return year;
            }
            if (i + 1 < limit && isLikelyWrappedPublicationLine(line)) {
                String joined = line + " " + clean(lines[i + 1]);
                if (isSourceCandidate(publicationCandidate(joined)) || isStrongSourceLine(joined)) {
                    Integer year = firstYear(joined);
                    if (year != null) return year;
                }
            }
            if (line.matches("20\\d{2}") && i + 1 < limit) {
                String joined = joinPublicationBlock(lines, i, limit);
                if (isSourceCandidate(publicationCandidate(joined)) || isStrongSourceLine(joined)) {
                    return Integer.valueOf(line);
                }
            }
        }

        // 某些会议 PDF 只在页眉单独打印年份，作为最后兜底扫描首页文本。
        for (int i = 0; i < Math.min(lines.length, 80); i++) {
            Integer year = firstYear(clean(lines[i]));
            if (year != null) return year;
        }
        return null;
    }

    private Integer firstYear(String value) {
        Matcher matcher = YEAR_PATTERN.matcher(value == null ? "" : value);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    private boolean isLikelyWrappedPublicationLine(String line) {
        return line.matches("(?i).*\\b(?:on|of|the|and|for|in|with)\\s*$")
                || line.endsWith("&")
                || line.endsWith("/");
    }

    private String publicationCandidate(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        String candidate = line.replaceAll("(?i)^in\\s+", "");
        candidate = PUBLICATION_PREFIX.matcher(candidate).replaceFirst("");
        candidate = LEADING_YEAR.matcher(candidate).replaceFirst("");
        candidate = candidate.replaceFirst("(?i)^published\\s+in\\s+", "");
        // ISBN、版权和 DOI 通常和会议名用竖线放在同一条文本流中，
        // 先截断后再做来源判断，避免整行因为包含 DOI 被丢弃。
        candidate = candidate.split("\\s*\\|\\s*", 2)[0];
        candidate = candidate.replaceFirst("(?i)\\s+(?:doi|digital object identifier)\\s*:.+$", "");
        candidate = ISSUE_SUFFIX.matcher(candidate).replaceFirst("");
        candidate = MONTH_OR_YEAR_SUFFIX.matcher(candidate).replaceFirst("");
        candidate = candidate.replaceFirst("\\s*,\\s*$", "").trim();
        return candidate;
    }

    private boolean isSourceCandidate(String candidate) {
        if (candidate.length() < 8 || candidate.length() > 240
                || candidate.matches("\\d+")
                || candidate.matches("(?i).*\\b(?:doi|digital object identifier|citation information)\\b.*")
                || looksLikeAuthorBlock(candidate)) {
            return false;
        }
        return PUBLICATION_SIGNAL.matcher(candidate).find();
    }

    private String extractAbstract(String text, int start) {
        String tail = text.substring(start);
        Matcher endMatcher = ABSTRACT_END.matcher(tail);
        String raw = endMatcher.find() ? tail.substring(0, endMatcher.start()) : tail;
        String cleaned = clean(raw);
        if (cleaned.length() <= MAX_ABSTRACT_LENGTH) {
            return cleaned.isBlank() ? null : cleaned;
        }
        return cleaned.substring(0, MAX_ABSTRACT_LENGTH).trim();
    }

    /** 提取摘要下方常见的 Keywords / Index Terms 行；没有关键词时返回 null。 */
    private String extractKeywords(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = KEYWORDS_HEADER.matcher(text);
        if (!matcher.find()) return null;

        String tail = text.substring(matcher.end());
        StringBuilder rawLines = new StringBuilder();
        for (String line : tail.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.matches("(?i)^(?:(?:[ivxlcdm]+|\\d+)\\s*\\.?\\s*)?"
                    + "(?:introduction|references|引言|参考文献)\\b.*")) {
                break;
            }
            if (!trimmed.isBlank()) {
                if (rawLines.length() > 0) rawLines.append('\n');
                rawLines.append(trimmed);
            }
        }
        String raw = rawLines.toString();
        String cleaned = clean(raw)
                .replaceAll("(?i)\\b(?:index\\s+terms?|keywords?|key\\s+words?)\\s*[-–—:：.]?\\s*", "")
                .replaceFirst("[.;。；]+$", "")
                .trim();
        if (cleaned.isBlank() || cleaned.length() > 1000) return null;
        return cleaned;
    }

    private String extractTitle(String text) {
        String[] lines = text.split("\\R");
        int limit = Math.min(lines.length, 100);

        // 常见 IEEE/ACM 排版：标题行之后紧邻作者行，作者行之前的连续文本就是标题。
        for (int i = 1; i < limit; i++) {
            if (!looksLikeAuthorLine(lines[i])) {
                continue;
            }
            List<String> titleLines = new ArrayList<>();
            for (int j = i - 1; j >= 0 && !lines[j].isBlank(); j--) {
                if (looksLikeHeaderNoise(lines[j])) {
                    break;
                }
                titleLines.add(0, lines[j].trim());
            }
            String candidate = clean(String.join(" ", titleLines));
            if (isTitleCandidate(candidate)) {
                return candidate;
            }
        }

        // 其他排版通常以空行分隔标题、作者和摘要，取摘要前倒数第一个像标题的文本块。
        String[] blocks = text.split("(?m)(?:\\r?\\n\\s*){2,}");
        for (int i = blocks.length - 1; i >= 0; i--) {
            String candidate = clean(blocks[i]);
            if (isTitleCandidate(candidate) && !looksLikeAuthorBlock(blocks[i])) {
                return candidate;
            }
        }
        return null;
    }

    private boolean looksLikeAuthorLine(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("member, ieee")
                || lower.contains("senior member")
                || lower.contains("corresponding author")
                || lower.contains("e-mail")
                || lower.contains("email")
                || line.contains("@")) {
            return true;
        }
        return extractAuthorNames(line).size() >= 2;
    }

    private boolean looksLikeAuthorBlock(String block) {
        return Arrays.stream(block.split("\\R"))
                .anyMatch(this::looksLikeAuthorLine);
    }

    private boolean looksLikeHeaderNoise(String line) {
        String trimmed = line.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return trimmed.matches("\\d{1,5}")
                || lower.contains("digital object identifier")
                || lower.matches(".*\\bvol\\.?\\s+\\d+.*")
                || lower.matches(".*\\bno\\.?\\s+\\d+.*")
                || lower.contains("issn");
    }

    private boolean isTitleCandidate(String candidate) {
        return candidate.length() >= 10
                && candidate.length() <= 300
                && !looksLikeHeaderNoise(candidate)
                && !candidate.matches("(?i).*\\b(?:abstract|摘要|received|revised|accepted)\\b.*");
    }

    /** 标题下方的作者行，兼容姓名后的单位上标、星号和 IEEE 会员标识。 */
    private String extractAuthors(String text, String title) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.split("\\R");
        int start = findTitleEnd(lines, title);
        if (start < 0) return null;

        List<String> authors = new ArrayList<>();
        boolean started = false;
        for (int i = start; i < Math.min(lines.length, start + 12); i++) {
            String line = clean(lines[i]);
            if (line.isBlank()) {
                if (started) break;
                continue;
            }
            List<String> lineAuthors = extractAuthorNames(line);
            if (!lineAuthors.isEmpty()) {
                authors.addAll(lineAuthors);
                started = true;
                continue;
            }
            if (isAffiliationLine(line)) {
                if (started) break;
                continue;
            }
            if (started) break;
        }
        return authors.isEmpty() ? null : String.join(", ", authors);
    }

    private int findTitleEnd(String[] lines, String title) {
        if (title == null || title.isBlank()) return -1;
        String target = compactForComparison(title);
        for (int i = 0; i < lines.length; i++) {
            String joined = "";
            for (int j = i; j < Math.min(lines.length, i + 6); j++) {
                joined = clean(joined + " " + lines[j]);
                if (compactForComparison(joined).equals(target)
                        || compactForComparison(joined).contains(target)) {
                    return j + 1;
                }
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if (looksLikeAuthorLine(lines[i])) return i;
        }
        return -1;
    }

    private String compactForComparison(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private List<String> extractAuthorNames(String line) {
        if (line == null || line.isBlank()) return List.of();
        String normalized = line
                .replaceAll("(?i),?\\s*(?:member|senior member|fellow),?\\s*ieee", "")
                .replaceAll("[0-9*†‡∗]+", "")
                .replaceAll("(?i)\\s+(?:and|&)\\s+", ",");
        List<String> names = new ArrayList<>();
        for (String part : normalized.split("[,;]")) {
            String name = clean(part).replaceAll("^[,;\\s]+|[,;\\s]+$", "");
            if (isNameCandidate(name)) names.add(name);
        }
        return names;
    }

    private boolean isNameCandidate(String value) {
        if (value.length() < 3 || value.length() > 80) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(?:university|laboratory|institute|department|school|college|"
                + "shenzhen|china|email|corresponding|ieee|abstract).*")) return false;
        return value.matches("(?:[A-ZÀ-ÖØ-Þ][\\p{L}'’-]+\\s+){1,3}[A-ZÀ-ÖØ-Þ][\\p{L}'’-]+")
                || value.matches("[\\p{IsHan}]{2,4}");
    }

    private boolean isAffiliationLine(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return line.contains("@") || lower.matches(".*(?:university|laboratory|institute|department|"
                + "school|college|shenzhen|china|corresponding|e-mail|email|member, ieee).*" );
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\u00ad', ' ')
                .replaceAll("(?<=\\p{L})-\\s*\\R\\s*(?=\\p{L})", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record Metadata(String title, String authors, Integer year, String abstractText, String source,
                           String keywords) {
    }
}
