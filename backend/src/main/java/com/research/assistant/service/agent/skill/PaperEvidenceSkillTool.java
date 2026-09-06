package com.research.assistant.service.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.research.assistant.service.agent.core.AgentToolDefinition;
import com.research.assistant.service.agent.core.AgentToolExecution;
import com.research.assistant.service.agent.core.AgentVisualContent;
import com.research.assistant.service.agent.core.PaperReadToolRegistry;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceVisualService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Model-facing adapter for the paper-evidence Skill. */
@Service
public class PaperEvidenceSkillTool {

    public static final String SKILL_NAME = "paper-evidence";

    private final PaperReadToolRegistry readTool;
    private final PaperSourceVisualService visualService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PaperEvidenceSkillTool(PaperReadToolRegistry readTool,
                                  PaperSourceVisualService visualService,
                                  ObjectMapper objectMapper) {
        this.readTool = readTool;
        this.visualService = visualService;
        this.objectMapper = objectMapper;
    }

    /** Constructor retained for focused tests that do not request visual evidence. */
    public PaperEvidenceSkillTool(PaperReadToolRegistry readTool) {
        this(readTool, null, new ObjectMapper());
    }

    public List<AgentToolDefinition> definitions() {
        return readTool.definitions();
    }

    public boolean supports(String toolName) {
        return "retrieve_paper_evidence".equals(toolName) || "read_pages".equals(toolName);
    }

    public AgentToolExecution execute(PaperSourceCatalog catalog, String toolName, String argumentsJson) {
        AgentToolExecution evidence = readTool.execute(catalog, toolName, argumentsJson);
        if (!"retrieve_paper_evidence".equals(toolName) || visualService == null) return evidence;
        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            Set<Integer> visualNeeds = visualNeedIndexes(arguments.path("needs"));
            if (visualNeeds.isEmpty()) return evidence;

            ObjectNode payload = (ObjectNode) objectMapper.readTree(evidence.resultJson());
            List<String> visualSourceIds = visualSourceIds(payload.path("evidenceNeeds"), visualNeeds);
            List<AgentVisualContent> visuals;
            String unavailableReason = null;
            try {
                visuals = visualService.render(catalog, visualSourceIds);
            } catch (RuntimeException visualFailure) {
                visuals = List.of();
                unavailableReason = "source_visual_unavailable";
            }
            ArrayNode descriptors = payload.putArray("visualSources");
            for (AgentVisualContent visual : visuals) {
                ObjectNode descriptor = descriptors.addObject();
                descriptor.put("sourceObjectId", visual.sourceObjectId());
                descriptor.put("page", visual.pageNumber());
                descriptor.put("contentType", visual.contentType());
                descriptor.put("width", visual.width());
                descriptor.put("height", visual.height());
            }
            payload.put("visualUnavailable", visuals.isEmpty());
            if (unavailableReason != null) payload.put("visualUnavailableReason", unavailableReason);
            return new AgentToolExecution(objectMapper.writeValueAsString(payload),
                    evidence.sourceObjectIds(), visuals);
        } catch (Exception error) {
            throw new IllegalArgumentException("unable to prepare visual paper evidence", error);
        }
    }

    private Set<Integer> visualNeedIndexes(JsonNode needs) {
        if (!needs.isArray()) return Set.of();
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int index = 0; index < needs.size(); index++) {
            if (needs.get(index).path("includeVisual").asBoolean(false)) indexes.add(index);
        }
        return Set.copyOf(indexes);
    }

    private List<String> visualSourceIds(JsonNode evidenceNeeds, Set<Integer> visualNeeds) {
        Set<String> ids = new LinkedHashSet<>();
        if (!evidenceNeeds.isArray()) return List.of();
        for (JsonNode need : evidenceNeeds) {
            if (!visualNeeds.contains(need.path("searchIndex").asInt(-1))) continue;
            for (JsonNode sourceId : need.path("sourceObjectIds")) {
                String value = sourceId.asText("").trim();
                if (!value.isBlank()) ids.add(value);
            }
        }
        return new ArrayList<>(ids);
    }
}
