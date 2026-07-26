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
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FormulaRegionImageServiceTest {

    @TempDir
    Path tempDir;

    private FormulaRegionImageService service() {
        return new FormulaRegionImageService(mock(FormulaRecognitionTelemetry.class));
    }

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

        FormulaRegionImage image = service().render(
                pdf, 1, new NormalizedBoundingBox(0.10, 0.20, 0.30, 0.15));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.png()));

        assertTrue(image.png().length > 100);
        assertEquals(image.width(), decoded.getWidth());
        assertEquals(image.height(), decoded.getHeight());
        assertTrue(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2) != 0xFFFFFFFF);
        assertTrue(image.dataUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    void masksNearbyContentOutsideTheSelectedGlyphBoxes() throws Exception {
        File pdf = tempDir.resolve("masked-selection.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 800));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(0, 0, 0);
                content.addRect(90, 560, 60, 40);
                content.addRect(210, 560, 60, 40);
                content.fill();
            }
            document.save(pdf);
        }

        NormalizedBoundingBox crop = new NormalizedBoundingBox(0.10, 0.20, 0.45, 0.15);
        NormalizedBoundingBox selected = new NormalizedBoundingBox(0.15, 0.25, 0.10, 0.05);
        FormulaRegionImage image = service().renderMasked(
                pdf, 1, crop, List.of(selected), "");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.png()));

        assertTrue(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2) != 0xFFFFFFFF);
        assertTrue(decoded.getWidth() < 220,
                "whitespace trimming must exclude the masked neighboring rectangle");
    }

    @Test
    void rejectsOversizedRegionsBeforeRendering() {
        assertThrows(IllegalArgumentException.class, () -> service().render(
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
        FormulaRegionImage image = service().render(
                pdf, 1, new NormalizedBoundingBox(0.65, 0.10, 0.15, 0.30));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(image.png()));

        assertTrue(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2) != 0xFFFFFFFF);
        assertTrue(decoded.getWidth() > 240);
        assertTrue(decoded.getHeight() > 360);
    }

    @Test
    void refusesToCropWhenThePdfChangedAfterArtifactResolution() throws Exception {
        File pdf = tempDir.resolve("versioned-formula-region.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(600, 800)));
            document.save(pdf);
        }
        String currentHash = PdfDocumentFingerprint.sha256(pdf);
        FormulaRegionImageService service = service();

        FormulaRegionImage image = service.render(pdf, 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.1), currentHash);
        assertTrue(image.png().length > 100);
        assertThrows(StaleLayoutArtifactException.class, () -> service.render(pdf, 1,
                new NormalizedBoundingBox(0.1, 0.1, 0.2, 0.1), "0".repeat(64)));
    }

    @Test
    void validatesTrimsAndBoundsAClientCanvasCrop() throws Exception {
        BufferedImage source = new BufferedImage(1600, 500, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = source.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.setColor(java.awt.Color.BLACK);
        graphics.fillRect(400, 180, 700, 90);
        graphics.dispose();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        ImageIO.write(source, "png", output);
        String dataUrl = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());

        FormulaRegionImage image = service().fromClientDataUrl(dataUrl);

        assertTrue(image.width() <= 1200);
        assertTrue(image.height() < 200);
        assertTrue(image.png().length > 100);
    }

    @Test
    void rejectsNonPngClientInput() {
        assertThrows(IllegalArgumentException.class,
                () -> service().fromClientDataUrl("data:text/plain;base64,SGVsbG8="));
    }

    private int pixel(BufferedImage image, double x, double y) {
        int px = Math.max(0, Math.min(image.getWidth() - 1,
                (int) Math.round(x * (image.getWidth() - 1))));
        int py = Math.max(0, Math.min(image.getHeight() - 1,
                (int) Math.round(y * (image.getHeight() - 1))));
        return image.getRGB(px, py) & 0xFFFFFF;
    }
}
