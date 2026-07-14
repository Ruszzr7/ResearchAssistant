package com.research.assistant.service.pdf;

import java.io.File;

/**
 * PDF 解析器抽象接口。
 * <p>
 * 不同实现可基于 PDFBox、Marker、MinerU、Grobid 等，返回统一字段。
 */
public interface PdfParser {

    /**
     * 解析整个 PDF 文件。
     */
    PdfParseResult parse(File file);

    /**
     * 仅解析前 N 页。
     */
    PdfParseResult parseFirstPages(File file, int maxPages);

    /**
     * 仅解析前 N 页，并优先保留页眉、页脚和页面版面信息，供元数据识别使用。
     * 默认实现兼容不支持独立版面模式的解析器。
     */
    default PdfParseResult parseFirstPagesForMetadata(File file, int maxPages) {
        return parseFirstPages(file, maxPages);
    }

    /**
     * 仅获取 PDF 总页数，不提取文本。
     */
    int countPages(File file);
}
