package com.research.assistant.service.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GapEvidenceScorerTest {

    @Test
    void shouldWeightRecentYearsHigher() {
        int current = java.time.Year.now().getValue();
        assertEquals(1.0, GapEvidenceScorer.weightByYear(String.valueOf(current - 1)), 0.001);
        assertEquals(0.6, GapEvidenceScorer.weightByYear(String.valueOf(current - 4)), 0.001);
        assertEquals(0.3, GapEvidenceScorer.weightByYear(String.valueOf(current - 10)), 0.001);
    }

    @Test
    void shouldHandleMalformedOrEmptyYear() {
        assertEquals(0.5, GapEvidenceScorer.weightByYear(""), 0.001);
        assertEquals(0.5, GapEvidenceScorer.weightByYear("not-a-year"), 0.001);
    }

    @Test
    void shouldScoreEvidenceList() {
        List<Map<String, Object>> evidence = List.of(
                evidence("2024"),
                evidence("2021"),
                evidence("2015")
        );
        double score = GapEvidenceScorer.score(evidence);
        assertEquals(1.9, score, 0.001);
    }

    @Test
    void shouldDetermineLevelByScore() {
        assertEquals("green", GapEvidenceScorer.determineLevel(1.5));
        assertEquals("yellow", GapEvidenceScorer.determineLevel(0.5));
        assertEquals("red", GapEvidenceScorer.determineLevel(0.0));
    }

    private Map<String, Object> evidence(String year) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("year", year);
        return m;
    }
}
