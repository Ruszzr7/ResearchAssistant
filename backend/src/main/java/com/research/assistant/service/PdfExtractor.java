package com.research.assistant.service;

import com.research.assistant.service.pdf.ExternalCommandPdfParser;
import com.research.assistant.service.pdf.FigureRegion;
import com.research.assistant.service.pdf.PdfParseResult;
import com.research.assistant.service.pdf.figure.FigureExtractor;
import com.research.assistant.service.pdf.formula.FormulaExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF 文本提取器 —— 统一入口。
 * <p>
 * 底层委托给 {@link com.research.assistant.service.pdf.PdfParser} 实现；
 * 默认使用 PDFBox，用户可在设置中启用外部解析器（Marker / MinerU / Grobid）。
 */
@Component
public class PdfExtractor {

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    private final ExternalCommandPdfParser pdfParser;
    private final FormulaExtractor formulaExtractor;
    private final FigureExtractor figureExtractor;

    public PdfExtractor(ExternalCommandPdfParser pdfParser,
                        FormulaExtractor formulaExtractor,
                        FigureExtractor figureExtractor) {
        this.pdfParser = pdfParser;
        this.formulaExtractor = formulaExtractor;
        this.figureExtractor = figureExtractor;
    }

    /**
     * 提取 PDF 全部文本。
     */
    public String extract(String pdfPath) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return "";
        }
        PdfParseResult result = pdfParser.parse(file);
        return result.success() ? result.text() : "";
    }

    /**
     * 仅提取 PDF 前 N 页文本。
     */
    public String extractFirstPages(String pdfPath, int maxPages) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return "";
        }
        PdfParseResult result = pdfParser.parseFirstPages(file, maxPages);
        return result.success() ? result.text() : "";
    }

    /**
     * 直接从上传的 MultipartFile 中提取前 N 页文本。
     */
    public String extractFromMultipartFile(MultipartFile file, int maxPages) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        try {
            Path temp = Files.createTempFile("upload-", ".pdf");
            file.transferTo(temp.toFile());
            PdfParseResult result = maxPages <= 0
                    ? pdfParser.parse(temp.toFile())
                    : pdfParser.parseFirstPages(temp.toFile(), maxPages);
            Files.deleteIfExists(temp);
            return result.success() ? result.text() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 获取 PDF 总页数。
     */
    public int countPages(String pdfPath) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return 0;
        }
        return pdfParser.countPages(file);
    }

    /**
     * 提取 PDF 中的公式（LaTeX 列表）。
     */
    public java.util.List<String> extractFormulas(String pdfPath) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return java.util.List.of();
        }
        return formulaExtractor.extract(file);
    }

    /**
     * 提取 PDF 中的图表区域。
     */
    public java.util.List<FigureRegion> extractFigures(String pdfPath) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return java.util.List.of();
        }
        return figureExtractor.extract(file);
    }

    private File resolveFile(String pdfPath) {
        if (pdfPath == null || pdfPath.isBlank()) {
            return null;
        }
        File dir = new File(pdfStorageDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        File file = new File(dir, pdfPath);
        return file.exists() ? file : null;
    }
}
