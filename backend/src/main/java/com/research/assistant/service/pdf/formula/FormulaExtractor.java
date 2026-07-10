package com.research.assistant.service.pdf.formula;

import java.io.File;
import java.util.List;

/**
 * 公式提取器接口。
 */
public interface FormulaExtractor {

    /**
     * 从 PDF 中提取公式，返回 LaTeX 字符串列表。
     */
    List<String> extract(File pdfFile);
}
