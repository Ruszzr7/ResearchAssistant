package com.research.assistant.service.identifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentifierExtractorTest {

    private final IdentifierExtractor extractor = new IdentifierExtractor();

    @Test
    void shouldExtractDoi() {
        String text = "This article is published under DOI 10.1038/nature14539.";
        IdentifierResult result = extractor.extract(text);
        assertEquals("10.1038/nature14539", result.getDoi());
        assertNull(result.getArxivId());
        assertTrue(result.isFound());
    }

    @Test
    void shouldExtractDoiWithInternalWhitespace() {
        String text = "DOI: 10. 1109 / TWC.2023.1234567";
        IdentifierResult result = extractor.extract(text);
        assertEquals("10.1109/TWC.2023.1234567", result.getDoi());
    }

    @Test
    void shouldExtractArxivNewFormat() {
        String text = "See arXiv:2301.12345v2 for details.";
        IdentifierResult result = extractor.extract(text);
        assertEquals("2301.12345", result.getArxivId());
        assertNull(result.getDoi());
    }

    @Test
    void shouldExtractArxivFromUrl() {
        String text = "Available at https://arxiv.org/abs/1706.03762.";
        IdentifierResult result = extractor.extract(text);
        assertEquals("1706.03762", result.getArxivId());
    }

    @Test
    void shouldExtractArxivOldFormat() {
        String text = "Preprint: arxiv:cs.CV/0402010";
        IdentifierResult result = extractor.extract(text);
        assertEquals("cs.CV/0402010", result.getArxivId());
    }

    @Test
    void shouldPreferArxivOverDoi() {
        String text = "Published in Nature, doi 10.1038/nature14539, also arXiv:2301.12345.";
        IdentifierResult result = extractor.extract(text);
        assertEquals("2301.12345", result.getArxivId());
        // DOI 在识别到 arXiv 时不返回
        assertNull(result.getDoi());
    }

    @Test
    void shouldReturnEmptyWhenNoIdentifier() {
        String text = "This is a plain text without any identifier.";
        IdentifierResult result = extractor.extract(text);
        assertFalse(result.isFound());
        assertNull(result.getDoi());
        assertNull(result.getArxivId());
    }

    @Test
    void shouldHandleBlankText() {
        IdentifierResult result = extractor.extract("  ");
        assertFalse(result.isFound());
    }
}
