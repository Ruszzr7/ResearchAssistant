package com.research.assistant.service.rag;

import com.research.assistant.entity.PaperAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DocumentChunker} 单元测试。
 */
class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void shouldCreateStructuredChunks() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(1L);
        analysis.setCoreContribution("We propose a novel attention mechanism.");
        analysis.setMethodSummary("Use transformer blocks.");
        analysis.setKeyFindingsJson("[\"finding one\", \"finding two\"]");
        analysis.setRawText("Paragraph one.\n\nParagraph two.");

        List<DocumentChunk> chunks = chunker.chunk(analysis);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(c -> "CONTRIBUTION".equals(c.chunkType()));
        assertThat(chunks).anyMatch(c -> "METHOD".equals(c.chunkType()));
        assertThat(chunks).anyMatch(c -> "FINDING".equals(c.chunkType()));
        assertThat(chunks).anyMatch(c -> "RAW".equals(c.chunkType()));
        assertThat(chunks).allMatch(c -> c.chunkOrder() != null);
    }

    @Test
    void shouldSkipEmptyFields() {
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(2L);
        analysis.setRawText("Only raw text.");

        List<DocumentChunk> chunks = chunker.chunk(analysis);

        assertThat(chunks).allMatch(c -> "RAW".equals(c.chunkType()));
    }

    @Test
    void shouldSplitLongRawText() {
        String repeated = "word ".repeat(200);
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(3L);
        analysis.setRawText(repeated);

        List<DocumentChunk> chunks = chunker.chunk(analysis);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).content().length()).isLessThanOrEqualTo(550);
        assertThat(chunks.get(0).charStart()).isNotNull();
        assertThat(chunks.get(0).charEnd()).isNotNull();
    }

    @Test
    void shouldSplitAnOverlongSentence() {
        String longContribution = "x".repeat(1300);
        PaperAnalysis analysis = new PaperAnalysis();
        analysis.setPaperId(4L);
        analysis.setCoreContribution(longContribution);

        List<DocumentChunk> chunks = chunker.chunk(analysis);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> chunk.content().length() <= 500);
    }
}
