package com.research.assistant.service.metadata;

import com.research.assistant.service.pdf.layout.PaperLayoutSemanticEnricher;
import com.research.assistant.service.pdf.layout.PdfBoxPaperLayoutParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfLayoutMetadataExtractorTest {

    @TempDir
    Path tempDir;

    private final PdfLayoutMetadataExtractor extractor = new PdfLayoutMetadataExtractor(
            new PdfBoxPaperLayoutParser(), new PaperLayoutSemanticEnricher());

    @Test
    void readsUsableEmbeddedMetadata() throws Exception {
        File pdf = tempDir.resolve("embedded.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("MapReduce: Simplified Data Processing on Large Clusters");
            info.setAuthor("Jeffrey Dean, Sanjay Ghemawat");
            info.setSubject("https://doi.org/10.1145/1327452.1327492");
            document.setDocumentInformation(info);
            document.save(pdf);
        }

        PdfDocumentMetadata result = extractor.extract(pdf, 5);

        assertThat(result.title()).isEqualTo("MapReduce: Simplified Data Processing on Large Clusters");
        assertThat(result.authors()).isEqualTo("Jeffrey Dean, Sanjay Ghemawat");
        assertThat(result.subject()).contains("10.1145/1327452.1327492");
    }

    @Test
    void ignoresGeneratedDocumentTitles() throws Exception {
        File pdf = tempDir.resolve("generated-title.pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("paper.dvi");
            document.setDocumentInformation(info);
            document.save(pdf);
        }

        assertThat(extractor.extract(pdf, 1).title()).isNull();
    }

    @Test
    void checksConfiguredRealMetadataSamples() {
        String mapReducePath = System.getenv("RA_METADATA_SAMPLE_MAPREDUCE");
        String shannonPath = System.getenv("RA_METADATA_SAMPLE_SHANNON");
        String gfsPath = System.getenv("RA_METADATA_SAMPLE_GFS");
        assumeTrue(mapReducePath != null && shannonPath != null,
                "Set real metadata samples to run local PDF regressions");

        PdfDocumentMetadata mapReduce = extractor.extract(new File(mapReducePath), 2);
        PdfDocumentMetadata shannon = extractor.extract(new File(shannonPath), 2);

        assertThat(mapReduce.title()).containsIgnoringCase("MapReduce");
        assertThat(mapReduce.authors()).contains("Jeffrey Dean", "Sanjay Ghemawat");
        assertThat(mapReduce.abstractText()).startsWithIgnoringCase("MapReduce is a programming model");
        assertThat(mapReduce.abstractText()).hasSizeLessThan(1_500);
        assertThat(shannon.title()).contains("Mathematical Theory of Communication");
        assertThat(shannon.authors()).contains("Shannon");
        if (gfsPath != null && !gfsPath.isBlank()) {
            PdfDocumentMetadata gfs = extractor.extract(new File(gfsPath), 2);
            assertThat(gfs.title()).isEqualTo("The Google File System");
            assertThat(gfs.authors()).contains("Sanjay Ghemawat", "Howard Gobioff", "Shun-Tak Leung")
                    .doesNotContain("Google");
        }
        System.out.printf("METADATA_SAMPLES mapReduceTitle=%s mapReduceAbstract=%d shannonTitle=%s%n",
                mapReduce.title(), mapReduce.abstractText() == null ? 0 : mapReduce.abstractText().length(),
                shannon.title());
    }
}
