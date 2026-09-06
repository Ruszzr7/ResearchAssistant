package com.research.assistant.service;

import com.research.assistant.service.pdf.PdfBoxPdfParser;
import com.research.assistant.service.pdf.PdfParseResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF 文本提取器 —— 统一入口。
 * <p>
 * 底层固定使用 PDFBox，保证本地部署不依赖外部解析命令。
 */
@Component
public class PdfExtractor {

    @Value("${app.storage.pdf-dir:../data/papers}")
    private String pdfStorageDir;

    private final PdfBoxPdfParser pdfParser;

    public PdfExtractor(PdfBoxPdfParser pdfParser) {
        this.pdfParser = pdfParser;
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
     * 提取供元数据识别使用的前 N 页文本，保留首页页眉、页脚和版面顺序。
     */
    public String extractFirstPagesForMetadata(String pdfPath, int maxPages) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return "";
        }
        PdfParseResult result = pdfParser.parseFirstPagesForMetadata(file, maxPages);
        return result.success() ? result.text() : "";
    }

    /**
     * 直接从上传的 MultipartFile 中提取前 N 页文本。
     */
    public String extractFromMultipartFile(MultipartFile file, int maxPages) {
        return extractFromMultipartFile(file, maxPages, false);
    }

    /**
     * 直接从上传的 MultipartFile 中提取供元数据识别使用的文本。
     */
    public String extractMetadataFromMultipartFile(MultipartFile file, int maxPages) {
        return extractFromMultipartFile(file, maxPages, true);
    }

    /**
     * 一次读取上传文件，同时取得「首页身份标识」与「前若干页元数据」文本。
     *
     * <p>DOI / arXiv ID 只能从首页识别，避免把后续正文或参考文献中的标识符误认为
     * 当前论文；标题、摘要、关键词则仍使用前几页文本。上传的 {@link MultipartFile}
     * 不能安全地多次 {@code transferTo}，所以在同一个临时文件上完成两次解析。</p>
     */
    public MetadataTextExtraction extractMetadataTextExtraction(MultipartFile file, int maxPages) {
        if (file == null || file.isEmpty()) {
            return new MetadataTextExtraction("", "");
        }
        try {
            Path temp = Files.createTempFile("upload-", ".pdf");
            try {
                file.transferTo(temp.toFile());
                return extractMetadataTextExtraction(temp.toFile(), maxPages);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            return new MetadataTextExtraction("", "");
        }
    }

    /**
     * 从已保存 PDF 中同时取得首页身份标识和前若干页元数据文本。
     */
    public MetadataTextExtraction extractMetadataTextExtraction(String pdfPath, int maxPages) {
        File file = resolveFile(pdfPath);
        if (file == null) {
            return new MetadataTextExtraction("", "");
        }
        return extractMetadataTextExtraction(file, maxPages);
    }

    private String extractFromMultipartFile(MultipartFile file, int maxPages, boolean metadataMode) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        try {
            Path temp = Files.createTempFile("upload-", ".pdf");
            file.transferTo(temp.toFile());
            PdfParseResult result = maxPages <= 0
                    ? pdfParser.parse(temp.toFile())
                    : metadataMode
                            ? pdfParser.parseFirstPagesForMetadata(temp.toFile(), maxPages)
                            : pdfParser.parseFirstPages(temp.toFile(), maxPages);
            Files.deleteIfExists(temp);
            return result.success() ? result.text() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private MetadataTextExtraction extractMetadataTextExtraction(File file, int maxPages) {
        PdfParseResult identity = pdfParser.parseFirstPagesForMetadata(file, 1);
        PdfParseResult metadata = maxPages <= 1
                ? identity
                : pdfParser.parseFirstPagesForMetadata(file, maxPages);
        return new MetadataTextExtraction(
                identity.success() ? identity.text() : "",
                metadata.success() ? metadata.text() : "");
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

    /** PDF 元数据识别所需的两种文本视图。 */
    public record MetadataTextExtraction(String identityText, String metadataText) {
    }
}
