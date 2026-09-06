package com.research.assistant.service.agent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.core.AgentToolExecution;
import com.research.assistant.service.agent.core.AgentVisualContent;
import com.research.assistant.service.agent.core.PaperReadToolRegistry;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceVisualService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperEvidenceSkillToolTest {

    @Test
    void returnsSourceLinkedVisualsWithoutEmbeddingBytesInJson() throws Exception {
        PaperReadToolRegistry readTool = mock(PaperReadToolRegistry.class);
        PaperSourceVisualService visualService = mock(PaperSourceVisualService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PaperSourceCatalog catalog = new PaperSourceCatalog(
                9, "h".repeat(64), "parser", 5, Map.of(), Map.of());
        String arguments = "{\"needs\":[{\"query\":\"figure\",\"includeVisual\":true}]}";
        when(readTool.execute(catalog, "retrieve_paper_evidence", arguments)).thenReturn(
                new AgentToolExecution("""
                        {"status":"found","evidenceNeeds":[
                        {"searchIndex":0,"sourceObjectIds":["src-figure"]}]}
                        """, Set.of("src-figure")));
        AgentVisualContent visual = new AgentVisualContent(
                "src-figure", 3, "FIGURE", "image/jpeg", new byte[]{1, 2, 3}, 320, 180);
        when(visualService.render(eq(catalog), eq(List.of("src-figure")))).thenReturn(List.of(visual));
        PaperEvidenceSkillTool skill = new PaperEvidenceSkillTool(readTool, visualService, objectMapper);

        AgentToolExecution result = skill.execute(catalog, "retrieve_paper_evidence", arguments);

        assertThat(result.visuals()).containsExactly(visual);
        assertThat(objectMapper.readTree(result.resultJson()).at("/visualSources/0/sourceObjectId").asText())
                .isEqualTo("src-figure");
        assertThat(result.resultJson()).doesNotContain("AQID", "base64", "bytes");
        verify(visualService).render(catalog, List.of("src-figure"));
    }
}
