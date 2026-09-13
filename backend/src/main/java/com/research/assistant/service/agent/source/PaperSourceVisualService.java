package com.research.assistant.service.agent.source;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.agent.core.AgentVisualContent;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.PdfDocumentFingerprint;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Renders bounded source-linked crops from the authoritative local PDF. */
@Service
public class PaperSourceVisualService {
    public static final int MAX_VISUALS = 2;
    static final float RENDER_DPI = 160f;
    static final int MAX_WIDTH = 1400;
    static final int MAX_HEIGHT = 1000;
    private static final double HORIZONTAL_PADDING = 0.012;
    private static final double VERTICAL_PADDING = 0.010;

    private final PaperMapper paperMapper;
    private final PaperPdfFileResolver fileResolver;

    public PaperSourceVisualService(PaperMapper paperMapper, PaperPdfFileResolver fileResolver) {
        this.paperMapper = paperMapper;
        this.fileResolver = fileResolver;
    }

    public List<AgentVisualContent> render(PaperSourceCatalog catalog, List<String> sourceIds) {
        if (catalog == null || sourceIds == null || sourceIds.isEmpty()) return List.of();
        Paper paper = paperMapper.selectById(catalog.paperId());
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        File pdf = fileResolver.resolveRequired(paper.getPdfPath());
        if (!catalog.documentHash().equals(PdfDocumentFingerprint.sha256(pdf))) {
            throw new StaleLayoutArtifactException();
        }

        Set<String> uniqueIds = new LinkedHashSet<>(sourceIds);
        List<CropRequest> requests = new ArrayList<>();
        for (String sourceId : uniqueIds) {
            SourceObject source = catalog.objects().get(sourceId);
            if (source == null) continue;
            catalog.locators().getOrDefault(sourceId, List.of()).stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            SourceLocator::pageNumber, java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()))
                    .forEach((page, locators) -> requests.add(new CropRequest(
                            sourceId, page, source.contentType().name(),
                            source.provenance().containsKey("sourceUnitKind"), union(source, locators))));
        }
        requests.sort(Comparator.comparingInt((CropRequest request) ->
                        priority(request.contentType(), request.completeUnit()))
                .thenComparingInt(CropRequest::pageNumber));
        List<CropRequest> selected = requests.stream().limit(MAX_VISUALS).toList();
        if (selected.isEmpty()) return List.of();

        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<AgentVisualContent> result = new ArrayList<>();
            int currentPage = -1;
            BufferedImage pageImage = null;
            for (CropRequest request : selected) {
                if (request.pageNumber() < 1 || request.pageNumber() > document.getNumberOfPages()) continue;
                if (currentPage != request.pageNumber()) {
                    currentPage = request.pageNumber();
                    pageImage = renderer.renderImageWithDPI(currentPage - 1, RENDER_DPI, ImageType.RGB);
                }
                BufferedImage crop = crop(pageImage, request.box());
                BufferedImage bounded = downscale(crop);
                byte[] bytes = encode(bounded);
                result.add(new AgentVisualContent(request.sourceObjectId(), request.pageNumber(),
                        request.contentType(), "image/jpeg", bytes, bounded.getWidth(), bounded.getHeight()));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("论文证据区域渲染失败", error);
        }
    }

    private static NormalizedBoundingBox union(SourceObject source, List<SourceLocator> locators) {
        // Figure locators expose caption rectangles to the UI while focusRects
        // retain the separate plot/illustration region needed by the model.
        // Other source types continue to render their complete content region;
        // in particular, a formula focus rect may contain only its number.
        List<NormalizedBoundingBox> boxes;
        if (source.contentType() == SourceContentType.FIGURE) {
            // The model needs the actual figure to read its curves/labels and the
            // complete caption to resolve the figure number and title.  The UI
            // still receives caption-only rects from SourceLocator; this union is
            // used only for the model-facing visual crop.
            boxes = new ArrayList<>();
            for (SourceLocator locator : locators) {
                boxes.addAll(locator.focusRects());
                boxes.addAll(locator.rects());
            }
        } else {
            boxes = locators.stream().flatMap(locator -> locator.rects().stream()).toList();
        }
        if (boxes.isEmpty()) throw new IllegalArgumentException("source locator has no rectangle");
        double left = boxes.stream().mapToDouble(NormalizedBoundingBox::x).min().orElse(0);
        double top = boxes.stream().mapToDouble(NormalizedBoundingBox::y).min().orElse(0);
        double right = boxes.stream().mapToDouble(NormalizedBoundingBox::right).max().orElse(1);
        double bottom = boxes.stream().mapToDouble(NormalizedBoundingBox::bottom).max().orElse(1);
        left = Math.max(0, left - HORIZONTAL_PADDING);
        top = Math.max(0, top - VERTICAL_PADDING);
        right = Math.min(1, right + HORIZONTAL_PADDING);
        bottom = Math.min(1, bottom + VERTICAL_PADDING);
        return new NormalizedBoundingBox(left, top, right - left, bottom - top);
    }

    private static BufferedImage crop(BufferedImage page, NormalizedBoundingBox box) {
        int x = clamp((int) Math.floor(box.x() * page.getWidth()), 0, page.getWidth() - 1);
        int y = clamp((int) Math.floor(box.y() * page.getHeight()), 0, page.getHeight() - 1);
        int right = clamp((int) Math.ceil(box.right() * page.getWidth()), x + 1, page.getWidth());
        int bottom = clamp((int) Math.ceil(box.bottom() * page.getHeight()), y + 1, page.getHeight());
        return page.getSubimage(x, y, right - x, bottom - y);
    }

    private static BufferedImage downscale(BufferedImage source) {
        double scale = Math.min(1, Math.min((double) MAX_WIDTH / source.getWidth(),
                (double) MAX_HEIGHT / source.getHeight()));
        if (scale >= 1) return source;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            throw new IllegalStateException("论文证据区域无法编码为图片");
        }
        return output.toByteArray();
    }

    private static int priority(String contentType, boolean completeUnit) {
        if (completeUnit) return 0;
        return switch (contentType) {
            case "FORMULA", "FIGURE", "TABLE", "ALGORITHM" -> 1;
            default -> 2;
        };
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record CropRequest(String sourceObjectId, int pageNumber, String contentType, boolean completeUnit,
                               NormalizedBoundingBox box) { }
}
