package com.research.assistant.service.ai.skill;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.SuggestionPojos.TagSuggestionResult;
import com.research.assistant.service.cache.RecommendationCache;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestTagsSkillTest {

    @Test
    void recommendsTagsFromPaperMetadataWithoutLegacyRagContext() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        ResearchToolAgent researchToolAgent = mock(ResearchToolAgent.class);
        LLMService llmService = mock(LLMService.class);
        SuggestTagsSkill skill = new SuggestTagsSkill(
                paperMapper, researchToolAgent, llmService, new RecommendationCache());

        Paper paper = new Paper();
        paper.setId(7L);
        paper.setTitle("Reliable multimodal paper understanding");
        paper.setAbstractText("A grounded method for reading figures and equations.");
        when(paperMapper.selectById(7L)).thenReturn(paper);

        TagSuggestionResult content = new TagSuggestionResult();
        content.setTags(List.of("multimodal learning", "document understanding"));
        @SuppressWarnings("unchecked")
        Result<TagSuggestionResult> result = mock(Result.class);
        when(result.content()).thenReturn(content);
        when(researchToolAgent.suggestTags(paper.getTitle(), paper.getAbstractText())).thenReturn(result);

        assertThat(skill.execute(new SkillContext("test"), 7L))
                .containsExactly("multimodal learning", "document understanding");
        verify(researchToolAgent).suggestTags(paper.getTitle(), paper.getAbstractText());
    }
}
