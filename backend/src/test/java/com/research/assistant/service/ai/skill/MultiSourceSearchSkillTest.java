package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.source.LiteratureCandidate;
import com.research.assistant.service.source.LiteratureSearchService;
import com.research.assistant.service.ai.skill.io.MultiSourceSearchInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiSourceSearchSkillTest {

    private LiteratureSearchService literatureSearchService;
    private PaperMapper paperMapper;
    private MultiSourceSearchSkill skill;

    @BeforeEach
    void setUp() {
        literatureSearchService = mock(LiteratureSearchService.class);
        paperMapper = mock(PaperMapper.class);
        skill = new MultiSourceSearchSkill(literatureSearchService, paperMapper);
    }

    @Test
    void shouldMergeExternalAndLocalCandidates() {
        LiteratureCandidate external = new LiteratureCandidate(
                "External Paper", "A", "2024", "summary", "", "",
                "https://example.org", "", "Semantic Scholar", "s2-1");
        when(literatureSearchService.search(anyList(), anyInt())).thenReturn(List.of(external));
        when(literatureSearchService.toResultMaps(anyList(), anyList()))
                .thenReturn(List.of(Map.of("title", "External Paper", "source", "Semantic Scholar")));

        Paper local = new Paper();
        local.setId(1L);
        local.setTitle("Local Paper");
        local.setYear(2023);
        local.setSource("Local");
        when(paperMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(local));

        Map<String, Object> elements = Map.of("keywords_en", List.of("transformer"));
        List<Map<String, Object>> result = skill.execute(new SkillContext("test"), new MultiSourceSearchInput(elements));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> "Local Paper".equals(r.get("title"))));
        assertTrue(result.stream().anyMatch(r -> "External Paper".equals(r.get("title"))));
    }

    @Test
    void shouldReturnEmptyOnBlankInput() {
        List<Map<String, Object>> result = skill.execute(new SkillContext("test"), new MultiSourceSearchInput(null));
        assertTrue(result.isEmpty());
    }
}
