package com.research.assistant.service.workbench;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.formula.region.FormulaRegionImage;
import com.research.assistant.service.pdf.formula.region.FormulaRegionImageService;
import com.research.assistant.service.pdf.layout.ClientContentSegmentType;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;

/** Builds one bounded, non-persisted image only when selected text contains mathematics. */
@Service
public class WorkbenchSelectionVisualEvidenceService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkbenchSelectionVisualEvidenceService.class);
    private static final double PADDING = 0.006;

    private final PaperMapper paperMapper;
    private final PaperPdfFileResolver fileResolver;
    private final FormulaRegionImageService imageService;

    public WorkbenchSelectionVisualEvidenceService(PaperMapper paperMapper,
                                                   PaperPdfFileResolver fileResolver,
                                                   FormulaRegionImageService imageService) {
        this.paperMapper = paperMapper;
        this.fileResolver = fileResolver;
        this.imageService = imageService;
    }

    public WorkbenchSelectionVisualEvidence create(SelectionAnchor anchor) {
        if (!requiresVisualEvidence(anchor)) return null;
        NormalizedBoundingBox bbox = union(anchor.boxes());
        if (bbox == null) {
            return unavailable(anchor, null, "选区坐标不足，数学内容需回原页核对");
        }
        try {
            Paper paper = paperMapper.selectById(anchor.paperId());
            if (paper == null) return unavailable(anchor, bbox, "论文不存在，数学内容需回原页核对");
            File pdf = fileResolver.resolveRequired(paper.getPdfPath());
            FormulaRegionImage image = imageService.renderMasked(
                    pdf, anchor.page(), bbox, anchor.boxes(), anchor.documentHash());
            return new WorkbenchSelectionVisualEvidence(
                    image.png(), anchor.page(), bbox, anchor.anchorText(),
                    "已附加 PDF 原始选区图像；以图像中的公式排版为准");
        } catch (RuntimeException error) {
            log.info("event=workbench_selection_visual_unavailable paperId={} page={} errorType={}",
                    anchor.paperId(), anchor.page(), error.getClass().getSimpleName());
            return unavailable(anchor, bbox, "选区图像暂不可用，数学内容需回原页核对");
        }
    }

    private boolean requiresVisualEvidence(SelectionAnchor anchor) {
        if (anchor == null) return false;
        if (anchor.contentType() == SelectionContentType.MATH_RICH_TEXT) return true;
        if (anchor.clientTextAnchor() == null) return false;
        return anchor.clientTextAnchor().contentSegments().stream()
                .anyMatch(segment -> segment.type() != ClientContentSegmentType.TEXT);
    }

    private WorkbenchSelectionVisualEvidence unavailable(SelectionAnchor anchor,
                                                         NormalizedBoundingBox bbox,
                                                         String message) {
        return new WorkbenchSelectionVisualEvidence(
                new byte[0], anchor.page(), bbox, anchor.anchorText(), message);
    }

    private NormalizedBoundingBox union(java.util.List<NormalizedBoundingBox> boxes) {
        if (boxes == null || boxes.isEmpty()) return null;
        double x = boxes.stream().mapToDouble(NormalizedBoundingBox::x).min().orElse(0);
        double y = boxes.stream().mapToDouble(NormalizedBoundingBox::y).min().orElse(0);
        double right = boxes.stream().mapToDouble(NormalizedBoundingBox::right).max().orElse(x);
        double bottom = boxes.stream().mapToDouble(NormalizedBoundingBox::bottom).max().orElse(y);
        x = Math.max(0, x - PADDING);
        y = Math.max(0, y - PADDING);
        right = Math.min(1, right + PADDING);
        bottom = Math.min(1, bottom + PADDING);
        return right > x && bottom > y
                ? new NormalizedBoundingBox(x, y, right - x, bottom - y)
                : null;
    }
}
