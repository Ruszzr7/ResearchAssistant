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
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.Iterator;

/** Renders the authoritative server-side PDF and crops a bounded formula region. */
@Service
public class FormulaRegionImageService {

    static final float RENDER_DPI = 160f;
    static final int MAX_WIDTH = 1200;
    static final int MAX_HEIGHT = 800;
    private static final int MAX_CLIENT_BYTES = 2 * 1024 * 1024;
    private static final long MAX_CLIENT_PIXELS = 12_000_000;
    private static final int TRIM_MARGIN_PIXELS = 8;
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

    /**
     * Accepts the current PDF.js canvas crop only as an untrusted recognition
     * candidate. It never establishes a formula anchor without user confirmation.
     */
    public FormulaRegionImage fromClientDataUrl(String dataUrl) {
        long started = telemetry.start();
        if (dataUrl == null || !dataUrl.startsWith("data:image/png;base64,")) {
            throw new IllegalArgumentException("公式预览必须是 PNG 图片");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("公式预览编码无效", error);
        }
        if (bytes.length == 0 || bytes.length > MAX_CLIENT_BYTES) {
            throw new IllegalArgumentException("公式预览大小超出限制");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("公式预览不是有效图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_CLIENT_PIXELS) {
                    throw new IllegalArgumentException("公式预览分辨率超出限制");
                }
                FormulaRegionImage image = encode(normalize(reader.read(0)));
                telemetry.stage("client_crop_normalize", "success", started);
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("公式预览无法读取", error);
        }
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
            FormulaRegionImage image = encode(normalize(visible));
            telemetry.stage("crop_encode", "success", cropStarted);
            return image;
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

    private BufferedImage normalize(BufferedImage source) {
        BufferedImage trimmed = trimWhitespace(source);
        return downscale(trimmed);
    }

    private BufferedImage trimWhitespace(BufferedImage source) {
        int left = source.getWidth();
        int top = source.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red >= 248 && green >= 248 && blue >= 248) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < left || bottom < top) return source;
        left = Math.max(0, left - TRIM_MARGIN_PIXELS);
        top = Math.max(0, top - TRIM_MARGIN_PIXELS);
        right = Math.min(source.getWidth() - 1, right + TRIM_MARGIN_PIXELS);
        bottom = Math.min(source.getHeight() - 1, bottom + TRIM_MARGIN_PIXELS);
        return source.getSubimage(left, top, right - left + 1, bottom - top + 1);
    }

    private FormulaRegionImage encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IllegalStateException("公式区域无法编码为 PNG");
        }
        byte[] png = output.toByteArray();
        telemetry.image(image.getWidth(), image.getHeight(), png.length);
        return new FormulaRegionImage(png, image.getWidth(), image.getHeight());
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PageCacheKey(String documentIdentity, int pageNumber) { }
}
