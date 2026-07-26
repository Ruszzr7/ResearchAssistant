package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PdfDocumentFingerprint;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the authoritative server-side PDF and crops a bounded formula region. */
@Service
public class FormulaRegionImageService {

    static final float RENDER_DPI = 160f;
    static final int MAX_WIDTH = 1800;
    static final int MAX_HEIGHT = 1000;
    private static final int MAX_CACHED_PAGES = 4;
    private static final int MASK_PADDING_PIXELS = 2;
    private final FormulaRecognitionTelemetry telemetry;
    private final Map<PageCacheKey, BufferedImage> pageCache =
            new LinkedHashMap<>(MAX_CACHED_PAGES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<PageCacheKey, BufferedImage> eldest) {
                    return size() > MAX_CACHED_PAGES;
                }
            };

    public FormulaRegionImageService(FormulaRecognitionTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public FormulaRegionImage render(File pdf, int pageNumber, NormalizedBoundingBox box) {
        return render(pdf, pageNumber, box, "");
    }

    public FormulaRegionImage render(File pdf, int pageNumber, NormalizedBoundingBox box,
                                     String expectedDocumentHash) {
        return render(pdf, pageNumber, box, List.of(), expectedDocumentHash);
    }

    public FormulaRegionImage renderMasked(File pdf,
                                           int pageNumber,
                                           NormalizedBoundingBox cropBox,
                                           List<NormalizedBoundingBox> visibleBoxes,
                                           String expectedDocumentHash) {
        if (visibleBoxes == null || visibleBoxes.isEmpty()) {
            throw new IllegalArgumentException("选区字形坐标不能为空");
        }
        return render(pdf, pageNumber, cropBox, List.copyOf(visibleBoxes), expectedDocumentHash);
    }

    private FormulaRegionImage render(File pdf,
                                      int pageNumber,
                                      NormalizedBoundingBox box,
                                      List<NormalizedBoundingBox> visibleBoxes,
                                      String expectedDocumentHash) {
        FormulaRegionGeometry.validate(box);
        if (expectedDocumentHash != null && !expectedDocumentHash.isBlank()
                && !expectedDocumentHash.equals(PdfDocumentFingerprint.sha256(pdf))) {
            throw new StaleLayoutArtifactException();
        }
        try {
            long renderStarted = telemetry.start();
            BufferedImage page = renderPage(pdf, pageNumber, expectedDocumentHash);
            telemetry.stage("page_render", "success", renderStarted);
            long cropStarted = telemetry.start();
            int x = clamp((int) Math.floor(box.x() * page.getWidth()), 0, page.getWidth() - 1);
            int y = clamp((int) Math.floor(box.y() * page.getHeight()), 0, page.getHeight() - 1);
            int right = clamp((int) Math.ceil(box.right() * page.getWidth()), x + 1, page.getWidth());
            int bottom = clamp((int) Math.ceil(box.bottom() * page.getHeight()), y + 1, page.getHeight());
            BufferedImage crop = page.getSubimage(x, y, right - x, bottom - y);
            BufferedImage visible = visibleBoxes.isEmpty()
                    ? crop
                    : maskUnselected(crop, visibleBoxes, page.getWidth(), page.getHeight(), x, y);
            BufferedImage bounded = downscale(visible);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(bounded, "png", output)) {
                throw new IllegalStateException("公式区域无法编码为 PNG");
            }
            byte[] png = output.toByteArray();
            telemetry.stage("crop_encode", "success", cropStarted);
            telemetry.image(bounded.getWidth(), bounded.getHeight(), png.length);
            return new FormulaRegionImage(png, bounded.getWidth(), bounded.getHeight());
        } catch (IOException e) {
            throw new IllegalArgumentException("公式区域渲染失败", e);
        }
    }

    private BufferedImage renderPage(File pdf,
                                     int pageNumber,
                                     String expectedDocumentHash) throws IOException {
        PageCacheKey key = new PageCacheKey(
                expectedDocumentHash == null || expectedDocumentHash.isBlank()
                        ? pdf.getAbsolutePath() + "|" + pdf.length() + "|" + pdf.lastModified()
                        : expectedDocumentHash,
                pageNumber);
        synchronized (pageCache) {
            BufferedImage cached = pageCache.get(key);
            if (cached != null) {
                telemetry.stage("page_cache", "hit", telemetry.start());
                return cached;
            }
        }
        telemetry.stage("page_cache", "miss", telemetry.start());
        BufferedImage rendered;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("公式页码超出 PDF 范围");
            }
            rendered = new PDFRenderer(document)
                    .renderImageWithDPI(pageNumber - 1, RENDER_DPI, ImageType.RGB);
        }
        synchronized (pageCache) {
            pageCache.put(key, rendered);
        }
        return rendered;
    }

    private BufferedImage maskUnselected(BufferedImage crop,
                                         List<NormalizedBoundingBox> visibleBoxes,
                                         int pageWidth,
                                         int pageHeight,
                                         int cropX,
                                         int cropY) {
        BufferedImage masked = new BufferedImage(
                crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = masked.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, masked.getWidth(), masked.getHeight());
            for (NormalizedBoundingBox box : visibleBoxes) {
                if (box == null) continue;
                int left = clamp((int) Math.floor(box.x() * pageWidth) - cropX
                        - MASK_PADDING_PIXELS, 0, crop.getWidth());
                int top = clamp((int) Math.floor(box.y() * pageHeight) - cropY
                        - MASK_PADDING_PIXELS, 0, crop.getHeight());
                int right = clamp((int) Math.ceil(box.right() * pageWidth) - cropX
                        + MASK_PADDING_PIXELS, 0, crop.getWidth());
                int bottom = clamp((int) Math.ceil(box.bottom() * pageHeight) - cropY
                        + MASK_PADDING_PIXELS, 0, crop.getHeight());
                if (right <= left || bottom <= top) continue;
                graphics.drawImage(crop,
                        left, top, right, bottom,
                        left, top, right, bottom, null);
            }
        } finally {
            graphics.dispose();
        }
        return masked;
    }

    private BufferedImage downscale(BufferedImage source) {
        double scale = Math.min(1, Math.min(
                (double) MAX_WIDTH / source.getWidth(),
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

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PageCacheKey(String documentIdentity, int pageNumber) { }
}
