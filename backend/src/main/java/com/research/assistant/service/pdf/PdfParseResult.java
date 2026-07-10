package com.research.assistant.service.pdf;

import java.util.Collections;
import java.util.List;

/**
 * PDF 解析结果。
 *
 * @param text       提取的文本
 * @param pageCount  页数（未知时为 -1）
 * @param success    是否成功
 * @param error      错误信息
 * @param formulas   识别到的公式列表（可选）
 * @param figures    识别到的图表区域列表（可选）
 */
public record PdfParseResult(String text,
                             int pageCount,
                             boolean success,
                             String error,
                             List<String> formulas,
                             List<FigureRegion> figures) {

    public static PdfParseResult success(String text, int pageCount) {
        return new PdfParseResult(text, pageCount, true, "", Collections.emptyList(), Collections.emptyList());
    }

    public static PdfParseResult success(String text, int pageCount, List<String> formulas, List<FigureRegion> figures) {
        return new PdfParseResult(text, pageCount, true, "",
                formulas != null ? formulas : Collections.emptyList(),
                figures != null ? figures : Collections.emptyList());
    }

    public static PdfParseResult failure(String error) {
        return new PdfParseResult("", -1, false, error, Collections.emptyList(), Collections.emptyList());
    }
}
