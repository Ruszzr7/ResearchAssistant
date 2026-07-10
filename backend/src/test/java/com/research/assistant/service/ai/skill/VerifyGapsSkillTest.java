package com.research.assistant.service.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.SemanticScholarFetcher;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.analysis.GapEvidenceScorer;
import com.research.assistant.service.rag.RagRetrievalService;
import com.research.assistant.service.rag.ScoredChunk;
import com.research.assistant.service.source.CitationNetworkExpansionService;
import com.research.assistant.service.source.LiteratureCandidate;
import com.research.assistant.service.source.LiteratureSearchService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerifyGapsSkillTest {

    private ResearchToolAgent researchToolAgent;
    private ArxivFetcher arxivFetcher;
    private SemanticScholarFetcher semanticScholarFetcher;
    private LLMService llmService;
    private ObjectMapper objectMapper;
    private RagRetrievalService ragRetrievalService;
    private CitationNetworkExpansionService citationNetworkExpansionService;
    private LiteratureSearchService literatureSearchService;
    private VerifyGapsSkill skill;

    @BeforeEach
    void setUp() {
        researchToolAgent = mock(ResearchToolAgent.class);
        arxivFetcher = mock(ArxivFetcher.class);
        semanticScholarFetcher = mock(SemanticScholarFetcher.class);
        llmService = mock(LLMService.class);
        objectMapper = new ObjectMapper();
        ragRetrievalService = mock(RagRetrievalService.class);
        citationNetworkExpansionService = mock(CitationNetworkExpansionService.class);
        literatureSearchService = mock(LiteratureSearchService.class);
        skill = new VerifyGapsSkill(researchToolAgent, arxivFetcher, semanticScholarFetcher,
                llmService, objectMapper, ragRetrievalService,
                citationNetworkExpansionService, literatureSearchService);
    }

    @Test
    void shouldReturnAgentResultWhenAvailable() throws Exception {
        String json = "[{\"gapTitle\":\"g1\",\"level\":\"red\",\"reason\":\"r1\",\"evidence\":[]}]";
        @SuppressWarnings("unchecked")
        Result<String> result = mock(Result.class);
        when(result.content()).thenReturn(json);
        when(researchToolAgent.verifyGaps(anyString())).thenReturn(result);

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### Gap 1\n描述");

        assertEquals(1, verified.size());
        assertEquals("g1", verified.get(0).get("gapTitle"));
        assertEquals("red", verified.get(0).get("level"));
    }

    @Test
    void fallbackShouldCollectEvidenceFromMultipleSources() throws Exception {
        when(researchToolAgent.verifyGaps(anyString())).thenThrow(new RuntimeException("agent fail"));

        Map<String, Object> arxivPaper = paper("Paper A", "arXiv", "2023", "We solve the gap.");
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of(arxivPaper));

        Map<String, Object> ssPaper = paper("Paper B", "Semantic Scholar", "2024", "Another approach.");
        when(semanticScholarFetcher.search(anyString(), anyInt())).thenReturn(List.of(ssPaper));

        String evidenceJson = """
                [{
                  "title": "Paper A",
                  "source": "arXiv",
                  "year": "2023",
                  "snippet": "We solve the gap.",
                  "url": "https://arxiv.org/abs/1"
                },{
                  "title": "Paper B",
                  "source": "Semantic Scholar",
                  "year": "2024",
                  "snippet": "Another approach.",
                  "url": "https://example.org/b"
                }]
                """;
        when(llmService.chat(anyString(), anyString())).thenReturn(evidenceJson);

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### [方法 Gap] Test Gap\nThis is a gap.");

        assertFalse(verified.isEmpty());
        Map<String, Object> first = verified.get(0);
        assertEquals("green", first.get("level"));
        assertEquals(2, ((List<?>) first.get("evidence")).size());
        assertTrue(first.get("reason").toString().contains("2"));
    }

    @Test
    void fallbackShouldReturnRedWhenNoCandidates() throws Exception {
        when(researchToolAgent.verifyGaps(anyString())).thenThrow(new RuntimeException("agent fail"));
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(semanticScholarFetcher.search(anyString(), anyInt())).thenReturn(List.of());

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### [方法 Gap] Empty Gap\nNo work.");

        assertFalse(verified.isEmpty());
        Map<String, Object> first = verified.get(0);
        assertEquals("red", first.get("level"));
        assertNotNull(first.get("evidence"));
        assertTrue(((List<?>) first.get("evidence")).isEmpty());
    }

    @Test
    void fallbackShouldIncludeLocalLibraryEvidence() throws Exception {
        when(researchToolAgent.verifyGaps(anyString())).thenThrow(new RuntimeException("agent fail"));
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(semanticScholarFetcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(ragRetrievalService.retrieveAndRerank(anyString(), eq(10), eq(0.65)))
                .thenReturn(List.of(new ScoredChunk(42L, "CONTRIBUTION", "we propose x", "核心贡献", 0.82)));

        String evidenceJson = """
                [{
                  "title": "本地论文库片段 #1",
                  "source": "Local Library",
                  "year": "",
                  "snippet": "we propose x",
                  "url": ""
                }]
                """;
        when(llmService.chat(anyString(), anyString())).thenReturn(evidenceJson);

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### [方法 Gap] Local Gap\nNeed x.");

        assertFalse(verified.isEmpty());
        Map<String, Object> first = verified.get(0);
        assertEquals("yellow", first.get("level"));
        List<?> evidence = (List<?>) first.get("evidence");
        assertEquals(1, evidence.size());
        Map<?, ?> e = (Map<?, ?>) evidence.get(0);
        assertEquals("Local Library", e.get("source"));
    }

    @Test
    void fallbackShouldIncludeNetworkCoverage() throws Exception {
        when(researchToolAgent.verifyGaps(anyString())).thenThrow(new RuntimeException("agent fail"));

        Map<String, Object> s2Paper = paper("S2 Paper", "Semantic Scholar", "2024", "covers gap");
        s2Paper.put("paperId", "s2id1");
        when(semanticScholarFetcher.search(anyString(), anyInt())).thenReturn(List.of(s2Paper));
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of());

        LiteratureCandidate related = new LiteratureCandidate(
                "Related Paper", "A Author", "2023", "related work", "", "",
                "https://example.org/related", "", "Semantic Scholar", "s2id2");
        when(citationNetworkExpansionService.expandByS2Id(eq("s2id1"), anyList(), anyInt()))
                .thenReturn(List.of(related));

        String evidenceJson = """
                [{
                  "title": "S2 Paper",
                  "source": "Semantic Scholar",
                  "year": "2024",
                  "snippet": "covers gap",
                  "url": "https://example.org/s2"
                }]
                """;
        when(llmService.chat(anyString(), anyString())).thenReturn(evidenceJson);

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### [方法 Gap] Network Gap\nNeed x.");

        assertFalse(verified.isEmpty());
        Map<String, Object> first = verified.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coverage = (List<Map<String, Object>>) first.get("networkCoverage");
        assertNotNull(coverage);
        assertEquals(1, coverage.size());
        assertEquals("Related Paper", coverage.get(0).get("title"));
    }

    @Test
    void fallbackShouldUseTimeWeightedScoring() throws Exception {
        when(researchToolAgent.verifyGaps(anyString())).thenThrow(new RuntimeException("agent fail"));
        Map<String, Object> old = paper("Old Paper", "arXiv", "2010", "old work");
        when(arxivFetcher.search(anyString(), anyInt())).thenReturn(List.of(old));
        when(semanticScholarFetcher.search(anyString(), anyInt())).thenReturn(List.of());

        String evidenceJson = """
                [{
                  "title": "Old Paper",
                  "source": "arXiv",
                  "year": "2010",
                  "snippet": "old work",
                  "url": ""
                }]
                """;
        when(llmService.chat(anyString(), anyString())).thenReturn(evidenceJson);

        List<Map<String, Object>> verified = skill.execute(new SkillContext("test"), "### [方法 Gap] Time Gap\nNeed x.");

        assertFalse(verified.isEmpty());
        Map<String, Object> first = verified.get(0);
        assertEquals("red", first.get("level"));
        String reason = String.valueOf(first.get("reason"));
        assertTrue(reason.contains("0.3"));
    }

    private Map<String, Object> paper(String title, String source, String year, String summary) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", title);
        p.put("source", source);
        p.put("published", year);
        p.put("summary", summary);
        p.put("sourceUrl", "https://example.org/" + title.replace(" ", ""));
        p.put("pdfUrl", "");
        return p;
    }
}
