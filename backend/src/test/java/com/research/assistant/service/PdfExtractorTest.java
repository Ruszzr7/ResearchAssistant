package com.research.assistant.service;

import com.research.assistant.service.pdf.ExternalCommandPdfParser;
import com.research.assistant.service.pdf.FigureRegion;
import com.research.assistant.service.pdf.FigureRegionType;
import com.research.assistant.service.pdf.PdfParseResult;
import com.research.assistant.service.pdf.figure.FigureExtractor;
import com.research.assistant.service.pdf.formula.FormulaExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link PdfExtractor} 单元测试。
 */
class PdfExtractorTest {

    private final ExternalCommandPdfParser pdfParser = mock(ExternalCommandPdfParser.class);
    private final FormulaExtractor formulaExtractor = mock(FormulaExtractor.class);
    private final FigureExtractor figureExtractor = mock(FigureExtractor.class);

    private final PdfExtractor extractor = new PdfExtractor(pdfParser, formulaExtractor, figureExtractor);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(extractor, "pdfStorageDir", tempDir.toString());
    }

    @Test
    void extractShouldDelegateToParser() throws IOException {
        Path pdf = createPdf("paper.pdf");
        when(pdfParser.parse(pdf.toFile())).thenReturn(PdfParseResult.success("extracted text", 1));

        String result = extractor.extract("paper.pdf");

        assertThat(result).isEqualTo("extracted text");
        verify(pdfParser).parse(pdf.toFile());
    }

    @Test
    void extractFirstPagesShouldDelegateWithMaxPages() throws IOException {
        Path pdf = createPdf("paper.pdf");
        when(pdfParser.parseFirstPages(pdf.toFile(), 2))
                .thenReturn(PdfParseResult.success("first pages", 2));

        String result = extractor.extractFirstPages("paper.pdf", 2);

        assertThat(result).isEqualTo("first pages");
        verify(pdfParser).parseFirstPages(pdf.toFile(), 2);
    }

    @Test
    void extractFirstPagesForMetadataShouldUseMetadataParser() throws IOException {
        Path pdf = createPdf("paper.pdf");
        when(pdfParser.parseFirstPagesForMetadata(pdf.toFile(), 2))
                .thenReturn(PdfParseResult.success("metadata pages", 2));

        String result = extractor.extractFirstPagesForMetadata("paper.pdf", 2);

        assertThat(result).isEqualTo("metadata pages");
        verify(pdfParser).parseFirstPagesForMetadata(pdf.toFile(), 2);
    }

    @Test
    void extractShouldReturnEmptyWhenFileMissing() {
        assertThat(extractor.extract("missing.pdf")).isEmpty();
        verifyNoInteractions(pdfParser);
    }

    @Test
    void extractFormulasShouldDelegateToFormulaExtractor() throws IOException {
        Path pdf = createPdf("paper.pdf");
        when(formulaExtractor.extract(pdf.toFile())).thenReturn(List.of("$a=b$"));

        List<String> result = extractor.extractFormulas("paper.pdf");

        assertThat(result).containsExactly("$a=b$");
    }

    @Test
    void extractFiguresShouldDelegateToFigureExtractor() throws IOException {
        Path pdf = createPdf("paper.pdf");
        FigureRegion region = new FigureRegion(1, 0, 0, 100, 100, "Fig 1", null, FigureRegionType.FIGURE);
        when(figureExtractor.extract(pdf.toFile())).thenReturn(List.of(region));

        List<FigureRegion> result = extractor.extractFigures("paper.pdf");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).caption()).isEqualTo("Fig 1");
    }

    private Path createPdf(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "%PDF-1.4 dummy");
        return path;
    }
}
