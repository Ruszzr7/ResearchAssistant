package com.research.assistant.service.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Folder;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.SuggestionPojos.FolderSuggestionResult;
import com.research.assistant.service.ai.skill.io.SuggestFolderInput;
import com.research.assistant.service.cache.RecommendationCache;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuggestFolderSkillTest {

    private FolderMapper folderMapper;
    private ResearchToolAgent researchToolAgent;
    private LLMService llmService;
    private SuggestFolderSkill skill;

    @BeforeEach
    void setUp() {
        PaperMapper paperMapper = mock(PaperMapper.class);
        folderMapper = mock(FolderMapper.class);
        researchToolAgent = mock(ResearchToolAgent.class);
        llmService = mock(LLMService.class);
        skill = new SuggestFolderSkill(
                paperMapper,
                folderMapper,
                researchToolAgent,
                llmService,
                new RecommendationCache(),
                new ObjectMapper());
    }

    @Test
    void shouldReturnNewFolderSuggestionWhenThereAreNoFolders() {
        when(folderMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = skill.execute(
                new SkillContext("test"), new SuggestFolderInput(null, "A paper"));

        assertNull(result.get("recommended"));
        assertEquals(true, result.get("suggestNew"));
        assertEquals("新文件夹", result.get("newName"));
    }

    @Test
    void shouldReturnSafeFallbackWhenAiServicesFail() {
        Folder folder = new Folder();
        folder.setId(5L);
        folder.setName("RSMA");
        when(folderMapper.selectList(any())).thenReturn(List.of(folder));
        doThrow(new RuntimeException("provider unavailable"))
                .when(researchToolAgent)
                .suggestFolder(anyString(), anyString(), anyString(), anyString());
        when(llmService.chat(anyString(), anyString())).thenReturn(null);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"), new SuggestFolderInput(null, "A paper"));

        assertNull(result.get("recommended"));
        assertEquals(false, result.get("suggestNew"));
        assertEquals("AI 推荐暂不可用，请手动选择文件夹", result.get("reason"));
    }

    @Test
    void shouldPreferNestedFolderReturnedByMetadataAgent() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder child = new Folder();
        child.setId(7L);
        child.setName("SumRate");
        child.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, child));
        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(7L);
        pojo.setReason("主指标是 Sum Rate");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(agentResult);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"),
                new SuggestFolderInput(null,
                        "Autonomous driving with RSMA-enabled sum-rate transmissions",
                        "The paper derives an ergodic sum-rate expression."));

        assertEquals(7L, result.get("recommended"));
        assertEquals("主指标是 Sum Rate", result.get("reason"));
    }

    @Test
    void shouldUseHierarchyAndAbstractForAgentRecommendationWhenNoDirectMatch() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder child = new Folder();
        child.setId(7L);
        child.setName("SumRate");
        child.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, child));
        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(7L);
        pojo.setReason("主题涉及吞吐率");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(
                eq("Autonomous driving in high mobility wireless networks"),
                eq(""),
                eq("The paper studies aggregate throughput under strict latency constraints."),
                anyString())).thenReturn(agentResult);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"),
                new SuggestFolderInput(null,
                        "Autonomous driving in high mobility wireless networks",
                        "The paper studies aggregate throughput under strict latency constraints."));

        assertEquals(7L, result.get("recommended"));
        assertEquals("主题涉及吞吐率", result.get("reason"));
    }

    @Test
    void shouldSuggestSiblingChildInsteadOfReturningBroadParent() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder existingChild = new Folder();
        existingChild.setId(7L);
        existingChild.setName("SumRate");
        existingChild.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, existingChild));
        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(5L);
        pojo.setReason("主题属于 RSMA");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(agentResult);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"),
                new SuggestFolderInput(null,
                        "RSMA with secrecy beamforming",
                        "The paper studies secrecy beamforming under RSMA."));

        assertNull(result.get("recommended"));
        assertEquals(true, result.get("suggestNew"));
        assertEquals(5L, ((Number) result.get("parentFolderId")).longValue());
        assertTrue(String.valueOf(result.get("newName")).length() > 0);
    }

    @Test
    void shouldCreateSiblingNameUsingDominantMetricCategory() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder sumRate = new Folder();
        sumRate.setId(7L);
        sumRate.setName("SumRate");
        sumRate.setParentId(5L);
        Folder fairness = new Folder();
        fairness.setId(8L);
        fairness.setName("Fairness");
        fairness.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, sumRate, fairness));
        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(5L);
        pojo.setReason("主题属于 RSMA");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(agentResult);

        Map<String, Object> result = skill.execute(new SkillContext("test"),
                new SuggestFolderInput(null, "RSMA and AAoI scheduling", "The paper minimizes AAoI."));

        assertNull(result.get("recommended"));
        assertEquals(true, result.get("suggestNew"));
        assertEquals("指标", result.get("newFolderCategory"));
        assertEquals("AAoI", result.get("newName"));
    }

    @Test
    void shouldNormalizeFolderIdFromTextFallback() {
        Folder folder = new Folder();
        folder.setId(5L);
        folder.setName("RSMA");
        when(folderMapper.selectList(any())).thenReturn(List.of(folder));
        doThrow(new RuntimeException("agent unavailable"))
                .when(researchToolAgent)
                .suggestFolder(anyString(), anyString(), anyString(), anyString());
        when(llmService.chat(anyString(), anyString()))
                .thenReturn("{\"folderId\":5,\"reason\":\"主题一致\",\"suggestNew\":false}");

        Map<String, Object> result = skill.execute(
                new SkillContext("test"), new SuggestFolderInput(null, "A paper"));

        assertEquals(5, ((Number) result.get("recommended")).intValue());
        assertEquals("主题一致", result.get("reason"));
    }

    @Test
    void shouldCreateErgodicRateInsteadOfLatencyForUrlccRatePaper() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder aoi = new Folder();
        aoi.setId(7L);
        aoi.setName("AoI");
        aoi.setParentId(5L);
        Folder latency = new Folder();
        latency.setId(8L);
        latency.setName("Latency");
        latency.setParentId(5L);
        Folder sumRate = new Folder();
        sumRate.setId(9L);
        sumRate.setName("SumRate");
        sumRate.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, aoi, latency, sumRate));

        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(8L);
        pojo.setReason("涉及 URLCC 延迟要求");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(agentResult);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"),
                new SuggestFolderInput(null,
                        "Rate-Splitting Multiple Access With Finite Blocklength and High Mobility for URLLC Transmissions",
                        "The paper derives an ergodic sum-rate under URLLC low-latency requirements.",
                        "Ergodic rate, finite blocklength, RSMA, URLLC"));

        assertNull(result.get("recommended"));
        assertEquals(true, result.get("suggestNew"));
        assertEquals(5L, ((Number) result.get("parentFolderId")).longValue());
        assertEquals("Ergodic Rate", result.get("newName"));
    }

    @Test
    void shouldPreferExistingChineseRateChildOverLatencyForUrlccRatePaper() {
        Folder parent = new Folder();
        parent.setId(5L);
        parent.setName("RSMA");
        Folder rate = new Folder();
        rate.setId(7L);
        rate.setName("和速率");
        rate.setParentId(5L);
        Folder latency = new Folder();
        latency.setId(8L);
        latency.setName("Latency");
        latency.setParentId(5L);
        when(folderMapper.selectList(any())).thenReturn(List.of(parent, rate, latency));

        FolderSuggestionResult pojo = new FolderSuggestionResult();
        pojo.setFolderId(8L);
        pojo.setReason("URLLC 的低时延约束");
        Result<FolderSuggestionResult> agentResult = mock(Result.class);
        when(agentResult.content()).thenReturn(pojo);
        when(researchToolAgent.suggestFolder(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(agentResult);

        Map<String, Object> result = skill.execute(
                new SkillContext("test"),
                new SuggestFolderInput(null,
                        "Rate-Splitting Multiple Access With Finite Blocklength and High Mobility for URLLC Transmissions",
                        "The paper derives an ergodic sum-rate under URLLC low-latency requirements.",
                        "Ergodic rate, finite blocklength, RSMA, URLLC"));

        assertEquals(7L, result.get("recommended"));
        assertEquals(false, result.get("suggestNew"));
        assertNull(result.get("newName"));
    }
}
