package com.research.assistant.service.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.agent.action.ActionTarget;
import com.research.assistant.service.agent.action.PaperActionResolver;
import com.research.assistant.service.agent.action.PaperActionType;
import com.research.assistant.service.agent.core.AgentToolDefinition;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Model-facing adapter for the paper-action Skill; ticket lifecycle remains runtime-owned. */
@Service
public class PaperActionSkillTool {

    public static final String SKILL_NAME = "paper-action";
    public static final String TOOL_NAME = "paper_action";
    private static final String ACTION_SCHEMA = """
            {"type":"object","properties":{
            "actionType":{"type":"string","enum":["JUMP","HIGHLIGHT","UNDERLINE","NOTE","COMMENT"],"description":"The single page operation explicitly requested by the user."},
            "sourceObjectId":{"type":"string","minLength":1,"maxLength":160,"description":"Trusted sourceObjectId from the current selection or a paper read result; never invent one."},
            "content":{"type":"string","maxLength":2000,"description":"Required only for NOTE or COMMENT; the note or comment text requested by the user."},
            "color":{"type":"string","maxLength":16,"description":"Optional annotation color, preferably a six-digit CSS hex color such as #ffee58."}},
            "required":["actionType","sourceObjectId"],"additionalProperties":false}
            """;

    private final PaperActionResolver actionResolver;

    public PaperActionSkillTool(PaperActionResolver actionResolver) {
        this.actionResolver = actionResolver;
    }

    public List<AgentToolDefinition> definitions() {
        return List.of(new AgentToolDefinition(TOOL_NAME,
                "Perform one validated page action after the applicable paper-action Skill is loaded.", ACTION_SCHEMA));
    }

    public boolean supports(String toolName) {
        return TOOL_NAME.equals(toolName);
    }

    public PreparedAction prepare(PaperSourceCatalog catalog, Set<String> readableSourceIds,
                                  String argumentsJson, ObjectMapper objectMapper) {
        if (catalog == null) throw new IllegalArgumentException("paper source is not ready");
        if (actionResolver == null) throw new IllegalStateException("paper actions are unavailable");
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            PaperActionType type = actionType(requiredText(args, "actionType"));
            String sourceId = requiredText(args, "sourceObjectId");
            if (!readableSourceIds.contains(sourceId)) {
                throw new IllegalArgumentException("action source was not read or selected: " + sourceId);
            }
            return prepareTrusted(catalog, type, sourceId, optionalText(args.path("content").asText(null)),
                    optionalText(args.path("color").asText(null)));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid arguments for paper action", error);
        }
    }

    /** Validates an already trusted client-side action without imposing an Agent read requirement. */
    public PreparedAction prepareExplicit(PaperSourceCatalog catalog, String typeName, String sourceObjectId,
                                          String content, String color) {
        return prepareTrusted(catalog, actionType(typeName), sourceObjectId, content, color);
    }

    private PreparedAction prepareTrusted(PaperSourceCatalog catalog, PaperActionType type, String sourceId,
                                          String content, String color) {
        if (catalog == null) throw new IllegalArgumentException("paper source is not ready");
        if (actionResolver == null) throw new IllegalStateException("paper actions are unavailable");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("explicit action requires a trusted sourceObjectId");
        }
        if ((type == PaperActionType.NOTE || type == PaperActionType.COMMENT) && content == null) {
            throw new IllegalArgumentException("note/comment content is required");
        }
        return new PreparedAction(type, actionResolver.resolve(catalog, sourceId), content, color);
    }

    private static PaperActionType actionType(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("actionType is required");
        try {
            return PaperActionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported paper action: " + value);
        }
    }

    private static String requiredText(JsonNode args, String name) {
        String value = optionalText(args.path(name).asText(null));
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PreparedAction(PaperActionType type, ActionTarget target, String content, String color) { }
}
