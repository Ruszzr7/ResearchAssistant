package com.research.assistant.service.pdf.layout;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfBoxPaperLayoutParserTest {

    @TempDir
    Path tempDir;

    private final PdfBoxPaperLayoutParser parser = new PdfBoxPaperLayoutParser();

    @Test
    void shouldBuildStableDoubleColumnReadingOrderAndNormalizedBoxes() throws Exception {
        File pdf = createDoubleColumnPdf(tempDir.resolve("double-column.pdf"));

        PaperLayoutArtifact artifact = parser.parse(42L, pdf);

        assertThat(artifact.paperId()).isEqualTo(42L);
        assertThat(artifact.documentHash()).matches("[0-9a-f]{64}");
        assertThat(artifact.parserVersion()).isEqualTo("pdfbox-layout-v4");
        assertThat(artifact.pageCount()).isEqualTo(1);
        assertThat(artifact.layoutConfidence()).isGreaterThanOrEqualTo(0.85);
        assertThat(artifact.blocks()).isNotEmpty();
        assertThat(artifact.blocks())
                .extracting(DocumentBlock::readingOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .range(0, artifact.blocks().size()).boxed().toList());
        assertThat(artifact.blocks()).allSatisfy(block -> {
            assertThat(block.page()).isEqualTo(1);
            assertThat(block.bbox().x()).isBetween(0.0, 1.0);
            assertThat(block.bbox().y()).isBetween(0.0, 1.0);
            assertThat(block.bbox().right()).isBetween(0.0, 1.0);
            assertThat(block.bbox().bottom()).isBetween(0.0, 1.0);
        });

        List<String> orderedText = artifact.blocks().stream().map(DocumentBlock::text).toList();
        assertThat(indexContaining(orderedText, "A centered paper title"))
                .isLessThan(indexContaining(orderedText, "Left column line one"));
        assertThat(indexContaining(orderedText, "Left column line four"))
                .isLessThan(indexContaining(orderedText, "Right column line one"));
        assertThat(indexContaining(orderedText, "Right column line one"))
                .isLessThan(indexContaining(orderedText, "Right column line four"));
        assertThat(artifact.blocks()).anySatisfy(block -> {
            assertThat(block.text()).contains("Running header");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.HEADER);
        });
        assertThat(artifact.blocks()).anySatisfy(block -> {
            assertThat(block.text()).contains("1");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.FOOTER);
        });
        assertThat(artifact.blocks()).anySatisfy(block -> {
            assertThat(block.text().replace(" ", "")).contains("IEEE2025");
            assertThat(block.role()).isEqualTo(DocumentBlockRole.MARGIN_METADATA);
            assertThat(block.bbox().x()).isLessThan(0.05);
        });
    }

    @Test
    void shouldKeepSingleColumnLinesInTopToBottomOrder() throws Exception {
        File pdf = createSingleColumnPdf(tempDir.resolve("single-column.pdf"));

        PaperLayoutArtifact artifact = parser.parse(7L, pdf);

        List<String> orderedText = artifact.blocks().stream().map(DocumentBlock::text).toList();
        assertThat(indexContaining(orderedText, "First paragraph line"))
                .isLessThan(indexContaining(orderedText, "Second paragraph line"));
        assertThat(indexContaining(orderedText, "Second paragraph line"))
                .isLessThan(indexContaining(orderedText, "Third paragraph line"));
        assertThat(artifact.layoutConfidence()).isBetween(0.75, 0.85);
    }

    @Test
    void shouldKeepMultilineFullWidthObjectTogetherBetweenColumnBands() throws Exception {
        File pdf = createDoubleColumnPdfWithMultilineFullWidthObject(
                tempDir.resolve("double-column-full-width-object.pdf"));

        PaperLayoutArtifact artifact = parser.parse(8L, pdf);

        List<String> orderedText = artifact.blocks().stream().map(DocumentBlock::text).toList();
        int objectLineOne = indexContaining(orderedText, "Cross-wide object line one");
        int objectLineTwo = indexContaining(orderedText, "Cross-wide object line two");
        int columnLineBetweenObjectLines = indexContaining(orderedText, "Column text beside object");

        assertThat(indexContaining(orderedText, "Left column upper line two"))
                .isLessThan(indexContaining(orderedText, "Right column upper line two"));
        assertThat(indexContaining(orderedText, "Right column upper line two"))
                .isLessThan(objectLineOne);
        assertThat(objectLineTwo).isEqualTo(objectLineOne + 1);
        assertThat(columnLineBetweenObjectLines).isGreaterThan(objectLineTwo);
        assertThat(objectLineTwo).isLessThan(indexContaining(orderedText, "Left column lower line"));
        assertThat(indexContaining(orderedText, "Left column lower line"))
                .isLessThan(indexContaining(orderedText, "Right column lower line"));
    }

    @Test
    void shouldSanityCheckConfiguredRealPaperSample() {
        String samplePath = System.getenv("RA_LAYOUT_SAMPLE");
        assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set RA_LAYOUT_SAMPLE to run the local real-paper layout check");
        File sample = new File(samplePath);
        assumeTrue(sample.isFile(), "Configured layout sample does not exist");

        PaperLayoutArtifact artifact = parser.parse(1L, sample);

        assertThat(artifact.pageCount()).isGreaterThan(1);
        assertThat(artifact.blocks().size()).isGreaterThan(100);
        assertThat(artifact.documentHash()).matches("[0-9a-f]{64}");
        assertThat(artifact.layoutConfidence()).isGreaterThan(0.6);
        assertThat(artifact.blocks())
                .extracting(DocumentBlock::readingOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .range(0, artifact.blocks().size()).boxed().toList());
        assertThat(artifact.blocks()).allSatisfy(block ->
                assertThat(block.layoutLane()).isNotEqualTo(DocumentLayoutLane.UNKNOWN));
        System.out.printf("LAYOUT_SAMPLE pages=%d blocks=%d confidence=%.3f parser=%s%n",
                artifact.pageCount(), artifact.blocks().size(), artifact.layoutConfidence(),
                artifact.parserVersion());
    }

    @Test
    void shouldKeepPaper190EquationAndTheoremRegression() {
        String samplePath = System.getenv("RA_LAYOUT_SAMPLE_190");
        assumeTrue(samplePath != null && !samplePath.isBlank(),
                "Set RA_LAYOUT_SAMPLE_190 to run the paper-190 regression check");
        File sample = new File(samplePath);
        assumeTrue(sample.isFile(), "Configured paper-190 sample does not exist");

        PaperLayoutArtifact artifact = parser.parse(190L, sample);
        List<DocumentBlock> equation16 = artifact.blocks().stream()
                .filter(block -> block.text().contains("(16)"))
                .toList();
        assertThat(equation16).isNotEmpty();
        assertThat(equation16).allSatisfy(block ->
                assertThat(block.bbox().width()).isLessThan(0.60));
        assertThat(artifact.blocks().stream()
                .filter(block -> block.text().contains("(11)") && block.text().contains("(17)"))
                .toList()).isEmpty();
        PaperLayoutArtifact semantic = new PaperLayoutSemanticEnricher()
                .enrich(artifact, PaperLayoutHints.empty());
        PaperSourceIndex sourceIndex = new PaperSourceIndexService().build(semantic);
        assertThat(sourceIndex.equations()).isNotEmpty();
        assertThat(sourceIndex.equations().stream()
                .filter(item -> item.relation() == EquationEntity.Relation.THEOREM_RESULT)
                .toList()).hasSizeGreaterThanOrEqualTo(2);
        EquationEntity lemma3 = sourceIndex.equations().stream()
                .filter(item -> item.statementLabel().equals("Lemma 3"))
                .findFirst().orElseThrow();
        assertThat(lemma3.number()).isEqualTo("18");
        EquationEntity commonRate = sourceIndex.equations().stream()
                .filter(item -> item.number().equals("21")).findFirst().orElseThrow();
        EquationEntity privateRate = sourceIndex.equations().stream()
                .filter(item -> item.number().equals("31")).findFirst().orElseThrow();
        assertThat(commonRate.statementLabel()).isEqualTo("Theorem 1");
        assertThat(privateRate.statementLabel()).isEqualTo("Theorem 2");
        assertThat(commonRate.definition().boxes()).allSatisfy(box ->
                assertThat(box.right()).isLessThan(0.51));
        assertThat(privateRate.definition().boxes()).allSatisfy(box ->
                assertThat(box.x()).isGreaterThan(0.50));
        assertThat(commonRate.definition().targetText()).contains("(21)");
        assertThat(privateRate.definition().targetText()).contains("(31)");
        System.out.printf("LAYOUT_SAMPLE_190 pages=%d blocks=%d confidence=%.3f parser=%s%n",
                artifact.pageCount(), artifact.blocks().size(), artifact.layoutConfidence(),
                artifact.parserVersion());
    }

    private File createDoubleColumnPdf(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "Running header", 50, 770, 8);
                writeLine(content, "A centered paper title", 215, 715, 16);
                writeLine(content, "Left column line one", 50, 650, 10);
                writeLine(content, "Right column line one", 330, 650, 10);
                writeLine(content, "Left column line two", 50, 630, 10);
                writeLine(content, "Right column line two", 330, 630, 10);
                writeLine(content, "Left column line three", 50, 610, 10);
                writeLine(content, "Right column line three", 330, 610, 10);
                writeLine(content, "Left column line four", 50, 590, 10);
                writeLine(content, "Right column line four", 330, 590, 10);
                writeLine(content, "1", 303, 20, 8);
                writeVerticalLine(content, "IEEE 2025", 18, 250, 8);
            }
            document.save(path.toFile());
        }
        return path.toFile();
    }

    private File createSingleColumnPdf(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "First paragraph line", 72, 700, 11);
                writeLine(content, "Second paragraph line", 72, 680, 11);
                writeLine(content, "Third paragraph line", 72, 660, 11);
            }
            document.save(path.toFile());
        }
        return path.toFile();
    }

    private File createDoubleColumnPdfWithMultilineFullWidthObject(Path path) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeLine(content, "Left column upper line one", 50, 700, 10);
                writeLine(content, "Right column upper line one", 330, 700, 10);
                writeLine(content, "Left column upper line two", 50, 680, 10);
                writeLine(content, "Right column upper line two", 330, 680, 10);
                writeLine(content,
                        "Cross-wide object line one contains enough text to span both columns",
                        60, 640, 10);
                writeLine(content, "Column text beside object", 50, 630, 10);
                writeLine(content,
                        "Cross-wide object line two remains part of the same wide object",
                        60, 620, 10);
                writeLine(content, "Right column lower line one", 330, 580, 10);
                writeLine(content, "Left column lower line one", 50, 580, 10);
                writeLine(content, "Right column lower line two", 330, 560, 10);
                writeLine(content, "Left column lower line two", 50, 560, 10);
            }
            document.save(path.toFile());
        }
        return path.toFile();
    }

    private void writeLine(PDPageContentStream content,
                           String text,
                           float x,
                           float y,
                           float fontSize) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private void writeVerticalLine(PDPageContentStream content,
                                   String text,
                                   float x,
                                   float y,
                                   float fontSize) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
        content.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, x, y));
        content.showText(text);
        content.endText();
    }

    private int indexContaining(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(expected)) {
                return index;
            }
        }
        throw new AssertionError("Expected layout text was not found: " + expected);
    }
}
