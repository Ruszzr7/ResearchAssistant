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
import com.research.assistant.service.agent.source.SourceEvidenceQuality;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Model-facing adapter for the paper-evidence Skill. */
@Service
public class PaperEvidenceSkillTool {

    public static final String SKILL_NAME = "paper-evidence";
    private static final Pattern FIGURE_REFERENCE = Pattern.compile(
            "(?i)\\bfig(?:ure)?s?\\.?\\s*([0-9IVX]+[a-z]?)\\b");

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
            ObjectNode payload = (ObjectNode) objectMapper.readTree(evidence.resultJson());
            if ("invalid_request".equals(payload.path("status").asText())) return evidence;
            Set<String> visualSourceIds = new LinkedHashSet<>(
                    visualSourceIds(payload.path("evidenceNeeds"), visualNeeds, payload.path("sources")));
            // Unreliable formula text is never sufficient for an exact expression.
            // Attach its existing page crop automatically instead of expecting the
            // model to infer a parser-quality flag and issue another read.
            for (String sourceId : evidence.sourceObjectIds()) {
                var source = catalog.objects().get(sourceId);
                if (source != null && "VISUAL_ONLY".equals(SourceEvidenceQuality.status(source))) {
                    visualSourceIds.add(sourceId);
                }
            }
            if (visualSourceIds.isEmpty()) return evidence;
            List<AgentVisualContent> visuals;
            String unavailableReason = null;
            try {
                visuals = visualService.render(catalog, new ArrayList<>(visualSourceIds));
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
            throw new IllegalArgumentException("无法准备论文视觉证据", error);
        }
    }

    private Set<Integer> visualNeedIndexes(JsonNode needs) {
        if (!needs.isArray()) return Set.of();
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int index = 0; index < needs.size(); index++) {
            JsonNode need = needs.get(index);
            boolean figureRequested = false;
            for (JsonNode type : need.path("contentTypes")) {
                if ("FIGURE".equals(type.asText())) {
                    figureRequested = true;
                    break;
                }
            }
            if (need.path("includeVisual").asBoolean(false) || figureRequested) indexes.add(index);
        }
        return Set.copyOf(indexes);
    }

    private List<String> visualSourceIds(JsonNode evidenceNeeds, Set<Integer> visualNeeds,
                                         JsonNode sources) {
        Set<String> ids = new LinkedHashSet<>();
        if (!evidenceNeeds.isArray()) return List.of();
        for (JsonNode need : evidenceNeeds) {
            if (!visualNeeds.contains(need.path("searchIndex").asInt(-1))) continue;
            Set<String> matchedFigureNumbers = matchedFigureNumbers(need.path("targetCoverage")
                    .path("matchedTargets"));
            Set<String> exactFigureIds = new LinkedHashSet<>();
            for (JsonNode sourceId : need.path("sourceObjectIds")) {
                String value = sourceId.asText("").trim();
                if (value.isBlank()) continue;
                JsonNode source = sourceById(sources, value);
                String figureNumber = sourceFigureNumber(source);
                if (!matchedFigureNumbers.isEmpty() && !figureNumber.isBlank()
                        && matchedFigureNumbers.contains(figureNumber)) {
                    exactFigureIds.add(value);
                }
            }
            // A figure-only Need with a confirmed target must not attach visual
            // crops for neighboring figures that happened to rank in the same
            // batch.  If compact metadata is unavailable, retain the old
            // behavior as a safe fallback.
            if (!exactFigureIds.isEmpty()) {
                ids.addAll(exactFigureIds);
            } else {
                for (JsonNode sourceId : need.path("sourceObjectIds")) {
                    String value = sourceId.asText("").trim();
                    if (!value.isBlank()) ids.add(value);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private JsonNode sourceById(JsonNode sources, String sourceObjectId) {
        if (!sources.isArray()) return objectMapper.createObjectNode();
        for (JsonNode source : sources) {
            if (sourceObjectId.equals(source.path("sourceObjectId").asText(""))) return source;
        }
        return objectMapper.createObjectNode();
    }

    private Set<String> matchedFigureNumbers(JsonNode matchedTargets) {
        Set<String> numbers = new LinkedHashSet<>();
        if (!matchedTargets.isArray()) return numbers;
        for (JsonNode target : matchedTargets) {
            String number = figureNumber(target.asText(""));
            if (!number.isBlank()) numbers.add(number);
        }
        return numbers;
    }

    private String sourceFigureNumber(JsonNode source) {
        if (source == null || !"FIGURE".equalsIgnoreCase(source.path("contentType").asText(""))) {
            return "";
        }
        String declared = source.path("figureNumber").asText("").strip();
        if (!declared.isBlank()) return declared.toLowerCase(java.util.Locale.ROOT);
        return figureNumber(source.path("content").asText(""));
    }

    private String figureNumber(String value) {
        Matcher matcher = FIGURE_REFERENCE.matcher(value == null ? "" : value);
        if (matcher.find()) return matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        String normalized = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[0-9]{1,4}[a-z]?") ? normalized : "";
    }
}
