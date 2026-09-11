package com.research.assistant.service.metadata;

import com.research.assistant.dto.EnrichmentResult;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.identifier.IdentifierExtractor;
import com.research.assistant.service.identifier.IdentifierResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataEnrichmentServiceTest {

    private static final String LOCAL_TITLE = "Enhancing Energy-Efficient URLLC in Cell-Free mMIMO Systems";

    @Mock private PdfExtractor pdfExtractor;
    @Mock private IdentifierExtractor identifierExtractor;
    @Mock private ArxivFetcher arxivFetcher;
    @Mock private CrossrefFetcher crossrefFetcher;
    @Mock private PaperMapper paperMapper;
    @Mock private PaperDocumentReviewer paperDocumentReviewer;

    private MetadataEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new MetadataEnrichmentService(
                pdfExtractor, identifierExtractor, arxivFetcher, crossrefFetcher, paperMapper,
                paperDocumentReviewer);
    }

    @Test
    void shouldIgnoreReferenceArxivRecordThatDoesNotMatchPdfTitle() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        String firstPage = """
                %s
                Alice Smith, Bob Jones
                Abstract—This paper studies reliable low-latency communication.
                """.formatted(LOCAL_TITLE);
        String metadataText = firstPage + "\n[15] Schulman et al., arXiv:1707.06347.";
        when(pdfExtractor.extractMetadataTextExtraction(eq(file), eq(5)))
                .thenReturn(new PdfExtractor.MetadataTextExtraction(firstPage, metadataText));
        when(paperDocumentReviewer.review(firstPage, metadataText))
                .thenReturn(new PaperDocumentReviewer.Review(PaperDocumentReviewer.Status.PAPER, ""));
        when(identifierExtractor.extract(firstPage)).thenReturn(new IdentifierResult(null, "1707.06347"));
        when(arxivFetcher.getMetadata("1707.06347")).thenReturn(Map.of(
                "title", "Proximal Policy Optimization Algorithms",
                "authors", "John Schulman",
                "published", "2017-07-20",
                "arxiv_id", "1707.06347",
                "source_url", "https://arxiv.org/pdf/1707.06347",
                "summary", "An unrelated reinforcement learning paper"));

        EnrichmentResult result = service.enrichFromPdf(file);

        assertEquals(LOCAL_TITLE, result.getTitle());
        assertNull(result.getFoundArxivId());
        assertNull(result.getArxivId());
        assertFalse(result.getMessage().isBlank());
        assertEquals(true, result.getMessage().contains("标题不一致"));
        verify(identifierExtractor).extract(firstPage);
        verify(crossrefFetcher, never()).fetch(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldApplyMatchingCrossrefRecordFromFirstPageDoi() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        String firstPage = """
                %s
                Alice Smith, Bob Jones
                DOI: 10.1109/LWC.2024.3373826
                Abstract—This paper studies reliable low-latency communication.
                """.formatted(LOCAL_TITLE);
        when(pdfExtractor.extractMetadataTextExtraction(eq(file), eq(5)))
                .thenReturn(new PdfExtractor.MetadataTextExtraction(firstPage, firstPage));
        when(paperDocumentReviewer.review(firstPage, firstPage))
                .thenReturn(new PaperDocumentReviewer.Review(PaperDocumentReviewer.Status.PAPER, ""));
        when(identifierExtractor.extract(firstPage))
                .thenReturn(new IdentifierResult("10.1109/LWC.2024.3373826", null));
        when(crossrefFetcher.fetch("10.1109/LWC.2024.3373826")).thenReturn(Map.of(
                "title", LOCAL_TITLE + " With Transceiver Impairments",
                "authors", "Alice Smith, Bob Jones",
                "year", "2024",
                "source", "IEEE Wireless Communications Letters",
                "doi", "10.1109/LWC.2024.3373826",
                "sourceUrl", "https://doi.org/10.1109/LWC.2024.3373826",
                "abstractText", ""));

        EnrichmentResult result = service.enrichFromPdf(file);

        assertEquals("10.1109/LWC.2024.3373826", result.getFoundDoi());
        assertEquals("IEEE Wireless Communications Letters", result.getSource());
        assertEquals(2024, result.getYear());
        assertEquals("已从 Crossref 补全元数据", result.getMessage());
        verify(arxivFetcher, never()).getMetadata(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldStopMetadataLookupForClearlyNonPaperPdf() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        String diagram = "Time Slot (Duration T) Signal Transmission BS User Scheduling CSI Feedback";
        when(pdfExtractor.extractMetadataTextExtraction(eq(file), eq(5)))
                .thenReturn(new PdfExtractor.MetadataTextExtraction(diagram, diagram));
        when(paperDocumentReviewer.review(diagram, diagram))
                .thenReturn(new PaperDocumentReviewer.Review(
                        PaperDocumentReviewer.Status.NOT_PAPER, "导入文件不是有效论文"));

        EnrichmentResult result = service.enrichFromPdf(file);

        assertEquals("NOT_PAPER", result.getDocumentType());
        assertEquals("导入文件不是有效论文", result.getMessage());
        assertNull(result.getTitle());
        verify(identifierExtractor, never()).extract(org.mockito.ArgumentMatchers.anyString());
        verify(crossrefFetcher, never()).fetch(org.mockito.ArgumentMatchers.anyString());
        verify(arxivFetcher, never()).getMetadata(org.mockito.ArgumentMatchers.anyString());
    }
}
