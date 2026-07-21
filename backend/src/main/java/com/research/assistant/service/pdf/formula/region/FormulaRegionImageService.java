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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** Renders the authoritative server-side PDF and crops a bounded formula region. */
@Service
public class FormulaRegionImageService {

    static final float RENDER_DPI = 200f;
    static final int MAX_WIDTH = 1800;
    static final int MAX_HEIGHT = 1000;

    FormulaRegionImage render(File pdf, int pageNumber, NormalizedBoundingBox box) {
        return render(pdf, pageNumber, box, "");
    }

    FormulaRegionImage render(File pdf, int pageNumber, NormalizedBoundingBox box,
                              String expectedDocumentHash) {
        FormulaRegionGeometry.validate(box);
        if (expectedDocumentHash != null && !expectedDocumentHash.isBlank()
                && !expectedDocumentHash.equals(PdfDocumentFingerprint.sha256(pdf))) {
            throw new StaleLayoutArtifactException();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("公式页码超出 PDF 范围");
            }
            BufferedImage page = new PDFRenderer(document)
                    .renderImageWithDPI(pageNumber - 1, RENDER_DPI, ImageType.RGB);
            int x = clamp((int) Math.floor(box.x() * page.getWidth()), 0, page.getWidth() - 1);
            int y = clamp((int) Math.floor(box.y() * page.getHeight()), 0, page.getHeight() - 1);
            int right = clamp((int) Math.ceil(box.right() * page.getWidth()), x + 1, page.getWidth());
            int bottom = clamp((int) Math.ceil(box.bottom() * page.getHeight()), y + 1, page.getHeight());
            BufferedImage crop = page.getSubimage(x, y, right - x, bottom - y);
            BufferedImage bounded = downscale(crop);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(bounded, "png", output)) {
                throw new IllegalStateException("公式区域无法编码为 PNG");
            }
            return new FormulaRegionImage(output.toByteArray(), bounded.getWidth(), bounded.getHeight());
        } catch (IOException e) {
            throw new IllegalArgumentException("公式区域渲染失败", e);
        }
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
}
