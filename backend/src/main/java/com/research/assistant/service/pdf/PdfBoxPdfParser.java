package com.research.assistant.service.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

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
        return parseInternal(file, maxPages);
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
        if (file == null || !file.exists()) {
            return PdfParseResult.failure("PDF 文件不存在");
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
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
