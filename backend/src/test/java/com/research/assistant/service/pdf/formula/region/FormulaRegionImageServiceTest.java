package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PdfDocumentFingerprint;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaRegionImageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersOnlyTheRequestedTopLeftNormalizedRegion() throws Exception {
        File pdf = tempDir.resolve("formula-region.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 800));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(0, 0, 0);
                content.addRect(60, 520, 180, 120);
                content.fill();
            }
            document.save(pdf);
        }

        FormulaRegionImage image = new FormulaRegionImageService().render(
                pdf, 1, new NormalizedBoundingBox(0.10, 0.20, 0.30, 0.15));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.png()));

        assertTrue(image.png().length > 100);
        assertEquals(image.width(), decoded.getWidth());
        assertEquals(image.height(), decoded.getHeight());
        assertTrue(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2) != 0xFFFFFFFF);
        assertTrue(image.dataUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    void rejectsOversizedRegionsBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> new FormulaRegionImageService().render(
                tempDir.resolve("missing.pdf").toFile(), 1,
                new NormalizedBoundingBox(0, 0, 1, 1)));
    }

    @Test
    void cropsTheSameVisibleRegionOnARotatedPage() throws Exception {
        File pdf = tempDir.resolve("rotated-formula-region.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 800));
            page.setRotation(90);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(0, 0, 0);
                content.addRect(60, 520, 180, 120);
                content.fill();
            }
            document.save(pdf);
        }

        // A 90-degree PDF viewport maps (x, y) to (y, x), so the visible
        // top-left normalized box is x=.65, y=.10, w=.15, h=.30.
        FormulaRegionImage image = new FormulaRegionImageService().render(
                pdf, 1, new NormalizedBoundingBox(0.65, 0.10, 0.15, 0.30));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.png()));

        assertTrue(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2) != 0xFFFFFFFF);
        assertTrue(decoded.getWidth() > 300);
        assertTrue(decoded.getHeight() > 450);
    }

    @Test
    void refusesToCropWhenThePdfChangedAfterArtifactResolution() throws Exception {
        File pdf = tempDir.resolve("versioned-formula-region.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(600, 800)));
            document.save(pdf);
        }
        String currentHash = PdfDocumentFingerprint.sha256(pdf);
        FormulaRegionImageService service = new FormulaRegionImageService();

        FormulaRegionImage image = service.render(pdf, 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.1), currentHash);
        assertTrue(image.png().length > 100);
        assertThrows(StaleLayoutArtifactException.class, () -> service.render(pdf, 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.1), "0".repeat(64)));
    }
}
