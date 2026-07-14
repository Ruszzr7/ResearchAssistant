package com.research.assistant.service.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataNormalizerTest {

    @Test
    void shouldDecodeHtmlEntitiesInSourceNames() {
        assertEquals("IEEE Communications Surveys & Tutorials",
                MetadataNormalizer.normalizeTitle("IEEE Communications Surveys &amp; Tutorials"));
    }

    @Test
    void shouldDecodeNumericHtmlEntities() {
        assertEquals("A & B", MetadataNormalizer.decodeHtmlEntities("A &#38; B"));
        assertEquals("A & B", MetadataNormalizer.decodeHtmlEntities("A &#x26; B"));
    }

    @Test
    void shouldNormalizeConferenceSourceWithoutYearPrefix() {
        assertEquals("19th International Symposium on Wireless Communication Systems (ISWCS)",
                MetadataNormalizer.normalizeSource("2024 19th International Symposium on Wireless Communication Systems (ISWCS)"));
    }
}
