package com.research.assistant.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/**
 * PDF 文本提取器 —— 从 PDF 文件中提取纯文本。
 * <p>
 * 使用 Apache PDFBox 3.x。提取结果暂存于 Paper.aiSummary 字段，
 * 待阶段三接入 LLM 后自动结构化为标题/作者/年份等。
 */
@Component
public class PdfExtractor {

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    /** 单个 PDF 提取文本的最大长度（约 15MB，低于 MEDIUMTEXT 上限） */
    private static final int MAX_TEXT_LENGTH = 15 * 1024 * 1024;

    /** 元数据识别时默认扫描前 N 页，避免大文件浪费 I/O */
    private static final int DEFAULT_ENRICHMENT_PAGES = 5;

    /**
     * 提取 PDF 全部文本，长度受 MAX_TEXT_LENGTH 限制，避免超出数据库存储上限。
     *
     * @param pdfPath 相对于 pdfStorageDir 的文件名
     * @return 提取的文本，失败返回空字符串
     */
    public String extract(String pdfPath) {
        File file = resolveFile(pdfPath);
        if (file == null || !file.exists()) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            return extractFromDocument(doc, null);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 仅提取 PDF 前 N 页文本，用于快速扫描 DOI / arXiv ID 等标识符。
     *
     * @param pdfPath  相对于 pdfStorageDir 的文件名
     * @param maxPages 最大页数，小于等于 0 表示全部页
     * @return 提取的文本，失败返回空字符串
     */
    public String extractFirstPages(String pdfPath, int maxPages) {
        File file = resolveFile(pdfPath);
        if (file == null || !file.exists()) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            return extractFromDocument(doc, maxPages);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 直接从上传的 MultipartFile 中提取前 N 页文本，用于导入预览阶段的元数据识别。
     * <p>
     * 该方法不落盘，避免 enrichment 预览阶段产生临时文件。
     *
     * @param file     PDF 文件
     * @param maxPages 最大页数，小于等于 0 表示全部页
     * @return 提取的文本，失败或文件为空返回空字符串
     */
    public String extractFromMultipartFile(MultipartFile file, int maxPages) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = file.getBytes();
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                return extractFromDocument(doc, maxPages);
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 统一提取逻辑。maxPages 为 null 或小于等于 0 时提取全部页。
     */
    private String extractFromDocument(PDDocument doc, Integer maxPages) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            if (maxPages != null && maxPages > 0) {
                int pageCount = doc.getNumberOfPages();
                stripper.setStartPage(1);
                stripper.setEndPage(Math.min(maxPages, pageCount));
            }
            String text = stripper.getText(doc);
            if (text == null) {
                return "";
            }
            text = text.trim();
            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    private File resolveFile(String pdfPath) {
        if (pdfPath == null || pdfPath.isBlank()) {
            return null;
        }
        File dir = new File(pdfStorageDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        return new File(dir, pdfPath);
    }
}
