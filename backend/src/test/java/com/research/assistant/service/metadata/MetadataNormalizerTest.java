package com.research.assistant.service.metadata;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class MetadataNormalizerTest {

    @Test
    void shouldNormalizePlainAuthors() {
        String json = MetadataNormalizer.normalizeAuthors("Alice Smith, Bob Jones");
        assertEquals("[{\"name\":\"Alice Smith\",\"role\":\"\"},{\"name\":\"Bob Jones\",\"role\":\"\"}]", json);
    }

    @Test
    void shouldNormalizeSemicolonAuthors() {
        String json = MetadataNormalizer.normalizeAuthors("Alice Smith; Bob Jones");
        assertEquals("[{\"name\":\"Alice Smith\",\"role\":\"\"},{\"name\":\"Bob Jones\",\"role\":\"\"}]", json);
    }

    @Test
    void shouldPreserveJsonAuthorsWithName() {
        String raw = "[{\"name\":\"Alice Smith\",\"role\":\"first\"}]";
        assertEquals(raw, MetadataNormalizer.normalizeAuthors(raw));
    }

    @Test
    void shouldNormalizeJsonTextArray() {
        String raw = "[\"Alice Smith\",\"Bob Jones\"]";
        String json = MetadataNormalizer.normalizeAuthors(raw);
        assertEquals("[{\"name\":\"Alice Smith\",\"role\":\"\"},{\"name\":\"Bob Jones\",\"role\":\"\"}]", json);
    }

    @Test
    void shouldNormalizeAuthorsFromList() {
        String json = MetadataNormalizer.normalizeAuthors(Arrays.asList("Alice", "Bob"));
        assertEquals("[{\"name\":\"Alice\",\"role\":\"\"},{\"name\":\"Bob\",\"role\":\"\"}]", json);
    }

    @Test
    void shouldReturnNullForEmptyAuthors() {
        assertNull(MetadataNormalizer.normalizeAuthors(""));
        assertNull(MetadataNormalizer.normalizeAuthors((String) null));
        assertNull(MetadataNormalizer.normalizeAuthors(java.util.Collections.emptyList()));
    }

    @Test
    void shouldNormalizeTitle() {
        assertEquals("Attention Is All You Need", MetadataNormalizer.normalizeTitle("Attention   Is\nAll You Need  "));
        assertNull(MetadataNormalizer.normalizeTitle("   "));
    }

    @Test
    void shouldNormalizeYear() {
        assertEquals(Integer.valueOf(2023), MetadataNormalizer.normalizeYear(2023));
        assertEquals(Integer.valueOf(2023), MetadataNormalizer.normalizeYear("2023"));
        assertEquals(Integer.valueOf(2023), MetadataNormalizer.normalizeYear("2023-01-15"));
        assertNull(MetadataNormalizer.normalizeYear("abc"));
        assertNull(MetadataNormalizer.normalizeYear(1800));
        assertNull(MetadataNormalizer.normalizeYear(null));
    }

    @Test
    void shouldNormalizeArxivId() {
        assertEquals("2301.12345", MetadataNormalizer.normalizeArxivId("2301.12345v2"));
        assertEquals("2301.12345", MetadataNormalizer.normalizeArxivId(" 2301.12345 "));
        assertNull(MetadataNormalizer.normalizeArxivId(null));
    }
}
