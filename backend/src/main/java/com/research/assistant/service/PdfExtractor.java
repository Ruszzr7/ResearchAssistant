package com.research.assistant.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    /**
     * 提取 PDF 全部文本。
     *
     * @param pdfPath 相对于 pdfStorageDir 的文件名
     * @return 提取的文本，失败返回空字符串
     */
    public String extract(String pdfPath) {
        File dir = new File(pdfStorageDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        File file = new File(dir, pdfPath);
        if (!file.exists()) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text != null ? text.trim().substring(0, Math.min(text.length(), 10000)) : "";
        } catch (Exception e) {
            return "";
        }
    }
}
