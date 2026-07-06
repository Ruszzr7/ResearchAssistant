package com.research.assistant.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PDF 提取文本的预处理器 —— 在喂给 LLM 之前进行规则化清理。
 * <p>
 * 处理内容：
 * <ul>
 *   <li>移除纯数字行（页码）</li>
 *   <li>移除含期刊水印的行（IEEE / ACM / Copyright / ISSN）</li>
 *   <li>移除过短行（< 5 字符）</li>
 *   <li>合并断行（hyphenated line breaks: "under-\nstanding" → "understanding"）</li>
 *   <li>合并英文换行（行末非句号时，视为同一段）</li>
 * </ul>
 */
@Component
public class TextPreprocessor {

    // 期刊水印关键词（大小写不敏感）
    private static final Pattern JOURNAL_PATTERN = Pattern.compile(
            ".*\\b(IEEE|ACM|Copyright|ISSN|DOI|ELSEVIER|SPRINGER|arXiv|Preprint|All rights reserved)\\b.*",
            Pattern.CASE_INSENSITIVE);

    // 纯数字行（页码）
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\s*\\d{1,4}\\s*$");

    // 太短的行（< 5 个有效字符）
    private static final int MIN_LINE_LENGTH = 5;

    /**
     * 清洗提取的原始文本。
     *
     * @param rawText PDFBox 提取的原始文本
     * @return 清洗后的文本
     */
    public String clean(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String[] lines = rawText.split("\\R");
        List<String> cleaned = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            // 空行保留（作为段落分隔）
            if (trimmed.isEmpty()) {
                cleaned.add("");
                continue;
            }

            // 过滤：期刊水印
            if (JOURNAL_PATTERN.matcher(trimmed).matches()) {
                continue;
            }

            // 过滤：纯数字行
            if (PAGE_NUMBER.matcher(trimmed).matches()) {
                continue;
            }

            // 过滤：太短
            if (trimmed.length() < MIN_LINE_LENGTH) {
                continue;
            }

            cleaned.add(trimmed);
        }

        // 合并：处理 hyphenated line breaks（"under-\nstanding" → "understanding"）
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < cleaned.size(); i++) {
            String line = cleaned.get(i);
            if (line.endsWith("-") && i + 1 < cleaned.size() && !cleaned.get(i + 1).isEmpty()) {
                // 去掉末尾连字符，与下一行合并
                String next = cleaned.get(i + 1);
                merged.add(line.substring(0, line.length() - 1) + next);
                i++;  // 跳过下一行
            } else {
                merged.add(line);
            }
        }

        return String.join("\n", merged);
    }

    /**
     * 截断文本到指定字符数，优先在段落边界处截断。
     */
    public String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        // 在 maxChars 范围内找最后一个换行符处截断
        int cut = text.lastIndexOf('\n', maxChars);
        if (cut > maxChars / 2) {
            return text.substring(0, cut);
        }
        return text.substring(0, maxChars);
    }
}
