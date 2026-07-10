package com.research.assistant.service.pdf.figure;

import com.research.assistant.service.pdf.FigureRegion;

import java.io.File;
import java.util.List;

/**
 * 图表提取器接口。
 */
public interface FigureExtractor {

    /**
     * 从 PDF 中提取图表区域（图片、表格位置等）。
     */
    List<FigureRegion> extract(File pdfFile);
}
