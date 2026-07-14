package com.research.assistant.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 Apache PDFBox 的 PDF 解析器 —— 默认实现。
 */
@Component
public class PdfBoxPdfParser implements PdfParser {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxPdfParser.class);

    /** 单个 PDF 提取文本的最大长度（约 15MB） */
    private static final int MAX_TEXT_LENGTH = 15 * 1024 * 1024;

    @Override
    public PdfParseResult parse(File file) {
        return parseInternal(file, null);
    }

    @Override
    public PdfParseResult parseFirstPages(File file, int maxPages) {
        return parseInternal(file, maxPages, false);
    }

    @Override
    public PdfParseResult parseFirstPagesForMetadata(File file, int maxPages) {
        // 双栏正文使用内容流顺序，避免摘要和右栏 Introduction 串读；左页边距
        // 的旋转出版信息则只有坐标排序时才会出现。因此只把坐标排序结果中的
        // 出版信息行提到正文前面，正文仍保持原来的单栏阅读顺序。
        PdfParseResult content = parseInternal(file, maxPages, false);
        PdfParseResult layout = parseInternal(file, maxPages, true);
        if (!content.success() || layout.text().isBlank()) {
            return content;
        }
        String publicationHeader = extractPublicationHeader(layout.text());
        if (publicationHeader.isBlank()) {
            return content;
        }
        return PdfParseResult.success(publicationHeader + "\n\n" + content.text(), content.pageCount());
    }

    private String extractPublicationHeader(String text) {
        Set<String> candidates = new LinkedHashSet<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (line.isBlank()) continue;
            boolean publication = line.matches("(?i).*\\b(?:conference|symposium|workshop|proceedings|"
                    + "journal|transactions|letters|magazine|review|vtc|iswcs|iccc)\\b.*");
            boolean metadata = line.matches("(?i).*\\b(?:ieee|acm|doi)\\b.*")
                    || line.matches(".*\\b20\\d{2}\\b.*");
            if (publication && metadata) {
                candidates.add(line);
            }
            if (line.matches("20\\d{2}") && i + 1 < lines.length) {
                String joined = joinLayoutPublicationBlock(lines, i);
                boolean joinedPublication = joined.matches("(?i).*\\b(?:conference|symposium|workshop|"
                        + "proceedings|journal|transactions|letters|magazine|review|vtc|iswcs|iccc)\\b.*");
                if (joinedPublication && joined.matches("(?i).*(?:ieee|acm|20\\d{2}).*")) {
                    candidates.add(joined);
                }
            }
        }
        return String.join("\n", candidates);
    }

    private String joinLayoutPublicationBlock(String[] lines, int start) {
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < Math.min(lines.length, start + 18); i++) {
            String line = lines[i].replaceAll("\\s+", " ").trim();
            if (line.isBlank()) break;
            if (joined.length() > 0) joined.append(' ');
            joined.append(line);
            if ("|".equals(line) || line.matches("(?i).*\\bdoi\\s*:.*")) break;
        }
        return joined.toString();
    }

    @Override
    public int countPages(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            log.warn("PDFBox 获取页数失败 {}: {}", file.getName(), e.getMessage());
            return 0;
        }
    }

    private PdfParseResult parseInternal(File file, Integer maxPages) {
        return parseInternal(file, maxPages, false);
    }

    private PdfParseResult parseInternal(File file, Integer maxPages, boolean preserveLayout) {
        if (file == null || !file.exists()) {
            return PdfParseResult.failure("PDF 文件不存在");
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // PDF 内容流顺序通常已经按栏组织；按坐标排序反而可能交错双栏文本。
            stripper.setSortByPosition(preserveLayout);
            int pageCount = doc.getNumberOfPages();
            if (maxPages != null && maxPages > 0) {
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(maxPages, pageCount));
            }
            String text = stripper.getText(doc);
            if (text == null) {
                text = "";
            }
            text = text.trim();
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            return PdfParseResult.success(text, pageCount);
        } catch (Exception e) {
            log.warn("PDFBox 解析失败 {}: {}", file.getName(), e.getMessage());
            return PdfParseResult.failure("PDFBox 解析失败: " + e.getMessage());
        }
    }
}
