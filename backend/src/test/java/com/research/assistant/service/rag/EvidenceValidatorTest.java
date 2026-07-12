package com.research.assistant.service.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceValidatorTest {

    private final EvidenceValidator validator = new EvidenceValidator();

    @Test
    void shouldAcceptOnlyCandidateBackedSnippetAndMetadata() {
        Map<String, Object> candidate = candidate("e1", "Paper A", "2024", "The method improves retrieval accuracy.");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("evidenceId", "e1");
        raw.put("title", "Forged title");
        raw.put("snippet", "The method improves retrieval accuracy.");
        raw.put("url", "https://attacker.invalid");

        List<Map<String, Object>> result = validator.validate(List.of(raw), List.of(candidate));

        assertEquals(1, result.size());
        assertEquals("Paper A", result.get(0).get("title"));
        assertEquals("2024", result.get(0).get("year"));
        assertEquals("https://example.org/e1", result.get(0).get("url"));
        assertEquals("VERIFIED", result.get(0).get("verificationStatus"));
    }

    @Test
    void shouldRejectSnippetNotPresentInCandidate() {
        Map<String, Object> candidate = candidate("e1", "Paper A", "2024", "The method improves retrieval accuracy.");
        Map<String, Object> raw = Map.of("evidenceId", "e1", "snippet", "This paper proves a different theorem.");

        assertTrue(validator.validate(List.of(raw), List.of(candidate)).isEmpty());
    }

    @Test
    void agentEvidenceWithoutStableIdIsNotAccepted() {
        Map<String, Object> raw = Map.of("title", "Paper A", "snippet", "some text", "url", "https://bad.invalid");

        assertTrue(validator.markAgentEvidenceUnverified(List.of(raw)).isEmpty());
    }

    private Map<String, Object> candidate(String evidenceId, String title, String year, String summary) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("evidenceId", evidenceId);
        candidate.put("title", title);
        candidate.put("published", year);
        candidate.put("summary", summary);
        candidate.put("source", "Local Fixture");
        candidate.put("sourceUrl", "https://example.org/" + evidenceId);
        return candidate;
    }
}
