package com.research.assistant.service.ai;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.SemanticScholarFetcher;
import com.research.assistant.service.metadata.CrossrefFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.research.assistant.service.rag.RagRetrievalService;
import com.research.assistant.service.rag.ScoredChunk;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ResearchTools} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ResearchToolsTest {

    @Mock
    private ArxivFetcher arxivFetcher;
    @Mock
    private SemanticScholarFetcher semanticScholarFetcher;
    @Mock
    private CrossrefFetcher crossrefFetcher;
    @Mock
    private PdfExtractor pdfExtractor;
    @Mock
    private PaperMapper paperMapper;
    @Mock
    private RagRetrievalService ragRetrievalService;

    private ResearchTools researchTools;

    @BeforeEach
    void setUp() {
        researchTools = new ResearchTools(arxivFetcher, semanticScholarFetcher, crossrefFetcher, pdfExtractor, paperMapper, ragRetrievalService);
    }

    @Test
    void searchArxivShouldClampMaxResults() throws Exception {
        when(arxivFetcher.search("transformer", 50)).thenReturn(List.of(Map.of("title", "T")));

        List<Map<String, Object>> result = researchTools.searchArxiv("transformer", 100);

        assertThat(result).hasSize(1);
        verify(arxivFetcher).search("transformer", 50);
    }

    @Test
    void searchArxivShouldReturnEmptyWhenBlankQuery() {
        assertThat(researchTools.searchArxiv("  ", 10)).isEmpty();
        verifyNoInteractions(arxivFetcher);
    }

    @Test
    void searchSemanticScholarShouldClampMaxResults() throws Exception {
        when(semanticScholarFetcher.search("llm", 50)).thenReturn(List.of(Map.of("title", "S")));

        List<Map<String, Object>> result = researchTools.searchSemanticScholar("llm", 200);

        assertThat(result).hasSize(1);
        verify(semanticScholarFetcher).search("llm", 50);
    }

    @Test
    void fetchCrossrefShouldReturnEmptyWhenDoiBlank() {
        assertThat(researchTools.fetchCrossref("")).isEmpty();
        verifyNoInteractions(crossrefFetcher);
    }

    @Test
    void searchLocalPapersByTitleShouldMapFields() {
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("Test");
        paper.setYear(2024);
        paper.setPdfPath("pdfs/test.pdf");
        when(paperMapper.selectList(any())).thenReturn(List.of(paper));

        List<Map<String, Object>> result = researchTools.searchLocalPapersByTitle("Test");

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("id")).isEqualTo(1L);
        assertThat(item.get("title")).isEqualTo("Test");
        assertThat(item.get("year")).isEqualTo(2024);
        assertThat(item.get("pdfPath")).isEqualTo("pdfs/test.pdf");
    }

    @Test
    void extractPdfTextByPathShouldTruncateLongText() {
        String longText = "a".repeat(9000);
        when(pdfExtractor.extract("pdfs/long.pdf")).thenReturn(longText);

        String result = researchTools.extractPdfTextByPath("pdfs/long.pdf", 0);

        assertThat(result).hasSizeLessThan(longText.length());
        assertThat(result).endsWith("字符）");
    }

    @Test
    void extractPdfTextByPathShouldRespectMaxPages() {
        when(pdfExtractor.extractFirstPages("pdfs/short.pdf", 2)).thenReturn("page text");

        String result = researchTools.extractPdfTextByPath("pdfs/short.pdf", 2);

        assertThat(result).isEqualTo("page text");
        verify(pdfExtractor, never()).extract(any());
    }

    @Test
    void searchKnowledgeBaseShouldReturnMappedChunks() {
        ScoredChunk chunk = new ScoredChunk(1L, "CONTRIBUTION", "core contribution", "核心贡献", 0.88);
        when(ragRetrievalService.retrieve("attention", 5, 0.65)).thenReturn(List.of(chunk));

        List<Map<String, Object>> result = researchTools.searchKnowledgeBase("attention");

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("paperId")).isEqualTo(1L);
        assertThat(item.get("chunkType")).isEqualTo("CONTRIBUTION");
        assertThat(item.get("content")).isEqualTo("core contribution");
        assertThat(item.get("source")).isEqualTo("核心贡献");
        assertThat(item.get("score")).isEqualTo(0.88);
    }

    @Test
    void searchKnowledgeBaseShouldReturnEmptyWhenBlankQuery() {
        assertThat(researchTools.searchKnowledgeBase("   ")).isEmpty();
        verifyNoInteractions(ragRetrievalService);
    }
}
