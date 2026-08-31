package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSkillRegistryTest {

    @Test
    void exposesNoPaperSkillsWhenThereIsNoCurrentSourceCatalog() {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        AgentSkillRegistry registry = new AgentSkillRegistry(reads);
        AgentContextSnapshot context = new AgentContextSnapshot(7L, null, null,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        assertThat(registry.available(context, "hello")).isEmpty();
        assertThat(registry.tools(context, "hello")).isEmpty();
    }

    @Test
    void exposesIndependentPaperSkillsAndDescribesExplicitPageActions() {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        when(reads.definitions()).thenReturn(List.of(
                new AgentToolDefinition("retrieve_paper_evidence", "read", "{\"type\":\"object\"}")));
        AgentSkillRegistry registry = new AgentSkillRegistry(reads);
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1, Map.of(), Map.of());
        AgentContextSnapshot context = new AgentContextSnapshot(7L, 9L, catalog,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        assertThat(registry.available(context, "请解释论文")).extracting(AgentSkill::id)
                .containsExactly("paper_evidence", "page_action");
        assertThat(registry.tools(context, "请解释论文")).extracting(AgentToolDefinition::name)
                .containsExactly("retrieve_paper_evidence", "paper_action");
        assertThat(registry.available(context, "请把式 21 高亮")).extracting(AgentSkill::id)
                .containsExactly("paper_evidence", "page_action");
        assertThat(registry.tools(context, "请把式 21 高亮")).extracting(AgentToolDefinition::name)
                .containsExactly("retrieve_paper_evidence", "paper_action");
        assertThat(registry.prompt(context, "请把式 21 高亮"))
                .contains("Optional capabilities available")
                .contains("按需批量查阅")
                .contains("多个证据需求", "再次调用", "CAPTION 只作辅助")
                .contains("只有用户明确要求");
    }

    @Test
    void keepsOverviewSkillAvailableWhenItsPreparedDataIsCurrentlyMissing() {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        PaperOverviewToolRegistry overview = mock(PaperOverviewToolRegistry.class);
        when(reads.definitions()).thenReturn(List.of());
        when(overview.definition()).thenReturn(new AgentToolDefinition(
                PaperOverviewToolRegistry.TOOL_NAME, "overview", "{\"type\":\"object\"}"));
        AgentSkillRegistry registry = new AgentSkillRegistry(reads, overview);
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1, Map.of(), Map.of());
        AgentContextSnapshot context = new AgentContextSnapshot(7L, 9L, catalog, false,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        assertThat(registry.tools(context, "请解释论文")).extracting(AgentToolDefinition::name)
                .contains(PaperOverviewToolRegistry.TOOL_NAME, "paper_action");
    }

    @Test
    void givesPageActionAnExplicitTrustedTargetSchema() throws Exception {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        when(reads.definitions()).thenReturn(List.of());
        AgentSkillRegistry registry = new AgentSkillRegistry(reads);
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1, Map.of(), Map.of());
        AgentContextSnapshot context = new AgentContextSnapshot(7L, 9L, catalog,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        AgentToolDefinition action = registry.tools(context, "请把当前选区高亮").stream()
                .filter(tool -> "paper_action".equals(tool.name())).findFirst().orElseThrow();
        JsonNode schema = new ObjectMapper().readTree(action.parametersJsonSchema());

        assertThat(schema.at("/properties/actionType/enum").toString())
                .contains("HIGHLIGHT", "JUMP", "NOTE");
        assertThat(schema.at("/properties/sourceObjectId/description").asText())
                .contains("Trusted sourceObjectId", "never invent");
        assertThat(schema.at("/properties/content/maxLength").asInt()).isEqualTo(2000);
        assertThat(schema.at("/required").toString()).contains("actionType", "sourceObjectId");
    }
}
