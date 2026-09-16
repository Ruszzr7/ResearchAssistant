package com.research.assistant.service.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSkillRegistryTest {

    @Test
    void exposesSkillMetadataBeforeThereIsACurrentSourceCatalog() {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        PaperOverviewToolRegistry overview = mock(PaperOverviewToolRegistry.class);
        when(reads.definitions()).thenReturn(List.of());
        when(overview.definition()).thenReturn(new AgentToolDefinition(
                PaperOverviewToolRegistry.TOOL_NAME, "overview", "{\"type\":\"object\"}"));
        AgentSkillRegistry registry = new AgentSkillRegistry(reads, overview);
        AgentContextSnapshot context = new AgentContextSnapshot(7L, null, null,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        assertThat(registry.bindings(context, "hello")).extracting(AgentSkillBinding::name)
                .containsExactly("paper-profile", "paper-evidence", "paper-action");
    }

    @Test
    void loadsStandardSkillMetadataAndKeepsHostToolsScopedToTheirSkill() {
        PaperReadToolRegistry reads = mock(PaperReadToolRegistry.class);
        when(reads.definitions()).thenReturn(List.of(
                new AgentToolDefinition("retrieve_paper_evidence", "read", "{\"type\":\"object\"}")));
        AgentSkillRegistry registry = new AgentSkillRegistry(reads);
        PaperSourceCatalog catalog = new PaperSourceCatalog(9L, "hash", "parser", 1, Map.of(), Map.of());
        AgentContextSnapshot context = new AgentContextSnapshot(7L, 9L, catalog,
                List.of(AgentChatEntry.system("system"), AgentChatEntry.user("question")),
                java.util.Set.of(), "{}");

        List<AgentSkillBinding> bindings = registry.bindings(context, "请解释论文");

        assertThat(bindings).extracting(AgentSkillBinding::name)
                .containsExactly("paper-evidence", "paper-action");
        assertThat(bindings.get(0).description()).contains("原始证据", "引用", "图像");
        assertThat(bindings.get(0).skill().content()).contains("retrieve_paper_evidence");
        assertThat(bindings.get(0).tools()).extracting(AgentToolDefinition::name)
                .containsExactly("retrieve_paper_evidence");
        assertThat(bindings.get(1).tools()).extracting(AgentToolDefinition::name)
                .containsExactly("paper_action");
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

        assertThat(registry.bindings(context, "请解释论文")).extracting(AgentSkillBinding::name)
                .containsExactly("paper-profile", "paper-evidence", "paper-action");
        assertThat(registry.bindings(context, "请解释论文").get(0).tools())
                .extracting(AgentToolDefinition::name)
                .containsExactly(PaperOverviewToolRegistry.TOOL_NAME);
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

        AgentToolDefinition action = registry.bindings(context, "请把当前选区高亮").stream()
                .flatMap(binding -> binding.tools().stream())
                .filter(tool -> "paper_action".equals(tool.name())).findFirst().orElseThrow();
        JsonNode schema = new ObjectMapper().readTree(action.parametersJsonSchema());

        assertThat(schema.at("/properties/actionType/enum").toString())
                .contains("HIGHLIGHT", "JUMP", "NOTE");
        assertThat(schema.at("/properties/sourceObjectId/description").asText())
                .contains("当前选区或论文读取结果中的可信 sourceObjectId", "绝不要编造");
        assertThat(schema.at("/properties/sourceObjectIds/maxItems").asInt()).isEqualTo(8);
        assertThat(schema.at("/properties/content/maxLength").asInt()).isEqualTo(2000);
        assertThat(schema.at("/properties/operations/items/properties/actionType/type").asText())
                .isEqualTo("string");
        assertThat(schema.path("properties").has("responseMode")).isFalse();
    }
}
