package com.research.assistant.service;

import com.research.assistant.service.pdf.PdfBoxPdfParser;
import com.research.assistant.service.pdf.PdfParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link PdfExtractor} 单元测试。
 */
class PdfExtractorTest {

    private final PdfBoxPdfParser pdfParser = mock(PdfBoxPdfParser.class);
    private final PdfExtractor extractor = new PdfExtractor(pdfParser);

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

    private Path createPdf(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "%PDF-1.4 dummy");
        return path;
    }
}
